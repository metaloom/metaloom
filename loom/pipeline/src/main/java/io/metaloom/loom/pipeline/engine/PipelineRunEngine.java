package io.metaloom.loom.pipeline.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphNode;
import io.metaloom.loom.pipeline.model.FilterBranch;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * Evaluates a {@link PipelineGraph} over a stream of media items, dispatching one
 * node at a time to a {@link NodeDispatcher}.
 *
 * <p>This is the Loom-side replacement for Cortex's {@code ReactivePipelineExecutor}.
 * The evaluation semantics are deliberately identical to the ones that engine
 * already implements and that existing tests pin down - only the location of the
 * decision changes.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>
 * engine.start();
 * engine.onItemDiscovered(mediaRef);   // repeatedly, as the source enumerates
 * engine.onSourceComplete(total);
 * engine.onNodeTaskResult(result);     // repeatedly, as workers reply
 * </pre>
 *
 * <p>The run completes when the source has finished <em>and</em> every discovered
 * item has a terminal result for every node.</p>
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 * <li>A node runs once all of its dependencies hold a terminal result.</li>
 * <li>A dependency in {@code FAILED} skips the dependent node <em>if that node is
 * blocking</em>. Note the direction: blocking is a property of the dependent node.</li>
 * <li>A dependency in {@code SKIPPED} does <strong>not</strong> cascade.</li>
 * <li>Filter routing consults only <em>direct</em> conditional dependencies, so
 * filter skipping is not transitive.</li>
 * <li>In a dry run every node is skipped and nothing is dispatched.</li>
 * <li>The source node's result is synthesised here rather than dispatched - its
 * output is derivable from the discovered item, so round-tripping it to a worker
 * would cost a network hop per item for nothing.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>All mutating entry points are synchronised on this instance. Callbacks fire on
 * the calling thread, so a listener must not block. Phase 1 keeps all state in
 * memory; a Loom restart loses in-flight runs.</p>
 */
public class PipelineRunEngine {

	private static final Logger log = LoggerFactory.getLogger(PipelineRunEngine.class);

	private static final String OUTPUT_PATH = "path";
	private static final String OUTPUT_SOURCE = "source";

	private final PipelineGraph graph;
	private final NodeDispatcher dispatcher;
	private final UUID runUuid;

	private final Map<String, ItemState> items = new LinkedHashMap<>();
	private final List<Consumer<RunSummary>> completionListeners = new ArrayList<>();

	private boolean started;
	private boolean sourceComplete;
	private boolean runComplete;
	private long startedAt;
	private long itemSequence;

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher, UUID runUuid) {
		this.graph = graph;
		this.dispatcher = dispatcher;
		this.runUuid = runUuid;
	}

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher) {
		this(graph, dispatcher, null);
	}

	/**
	 * Register a callback fired exactly once, when the run reaches a terminal state.
	 *
	 * @param listener receives the run counters
	 */
	public synchronized void onCompletion(Consumer<RunSummary> listener) {
		completionListeners.add(listener);
	}

	/**
	 * Begin the run.
	 *
	 * <p>A disabled pipeline completes immediately with no work, matching the existing
	 * executor's behaviour.</p>
	 */
	public synchronized void start() {
		if (started) {
			throw new IllegalStateException("Run already started");
		}
		started = true;
		startedAt = System.currentTimeMillis();

		if (!graph.isEnabled()) {
			log.info("Pipeline '{}' is disabled - completing run without work", graph.getName());
			sourceComplete = true;
			checkComplete();
		}
	}

	/**
	 * Called for each media item the source enumerates.
	 *
	 * <p>The source node's own result is recorded here, which is what makes the rest
	 * of the graph become ready.</p>
	 *
	 * @param media the discovered item
	 * @return the item id assigned by the engine
	 */
	public synchronized String onItemDiscovered(MediaRef media) {
		requireStarted();
		if (sourceComplete) {
			throw new IllegalStateException("Source already reported complete for run " + runUuid);
		}
		if (!graph.isEnabled()) {
			return null;
		}

		String itemId = "item-" + (++itemSequence);
		ItemState state = new ItemState(itemId, media);
		items.put(itemId, state);

		PipelineGraphNode source = graph.getSourceNode();
		Map<String, Object> outputs = new LinkedHashMap<>();
		outputs.put(OUTPUT_PATH, media.getPath());
		outputs.put(OUTPUT_SOURCE, source.getKind());

		if (graph.isDryRun()) {
			state.record(NodeTaskResult.skipped(source.getId(), "dry-run"));
		} else {
			state.record(NodeTaskResult.completed(null, source.getId(), 0, outputs));
		}

		advance(state);
		checkComplete();
		return itemId;
	}

	/**
	 * Called once the source has finished enumerating.
	 *
	 * @param totalCount number of items the source produced, for reconciliation
	 */
	public synchronized void onSourceComplete(long totalCount) {
		requireStarted();
		sourceComplete = true;
		if (totalCount != items.size()) {
			log.warn("Run {} source reported {} items but {} were received", runUuid, totalCount, items.size());
		}
		checkComplete();
	}

	/**
	 * Called when a worker reports the outcome of a dispatched task.
	 *
	 * <p>Unknown or duplicate task ids are logged and ignored rather than throwing:
	 * once retries exist, duplicate delivery is expected.</p>
	 *
	 * @param itemId the item the task belonged to
	 * @param result the outcome
	 */
	public synchronized void onNodeTaskResult(String itemId, NodeTaskResult result) {
		requireStarted();
		ItemState state = items.get(itemId);
		if (state == null) {
			log.warn("Result for unknown item '{}' in run {} - ignoring", itemId, runUuid);
			return;
		}
		if (state.isSettled(result.getNodeId())) {
			log.warn("Duplicate result for node '{}' on item '{}' - ignoring", result.getNodeId(), itemId);
			return;
		}
		state.record(result);
		advance(state);
		checkComplete();
	}

	/** @return true when the run has reached a terminal state */
	public synchronized boolean isComplete() {
		return runComplete;
	}

	/** @return current counters; meaningful once {@link #isComplete()} is true */
	public synchronized RunSummary summary() {
		return buildSummary();
	}

	/**
	 * @param itemId the item
	 * @return its state, or null when unknown
	 */
	public synchronized ItemState getItem(String itemId) {
		return items.get(itemId);
	}

	/** @return all item states, in discovery order */
	public synchronized Map<String, ItemState> getItems() {
		return new LinkedHashMap<>(items);
	}

	/**
	 * Dispatch every node of this item that has become ready, recording immediate
	 * results for nodes that are skipped rather than executed.
	 */
	private void advance(ItemState state) {
		boolean progressed = true;
		// A skip settles a node without a round trip, which can immediately make its
		// children ready - so keep going until nothing new settles.
		while (progressed) {
			progressed = false;
			for (String nodeId : graph.getTopologicalOrder()) {
				if (state.isSettled(nodeId) || state.isInFlight(nodeId)) {
					continue;
				}
				PipelineGraphNode node = graph.getNode(nodeId);
				if (!dependenciesSettled(state, node)) {
					continue;
				}

				NodeTaskResult skip = evaluateSkip(state, node);
				if (skip != null) {
					state.record(skip);
					progressed = true;
					continue;
				}

				// A rejected dispatch settles the node immediately, which can unblock
				// children in this same pass.
				progressed |= dispatch(state, node);
			}
		}
	}

	private boolean dependenciesSettled(ItemState state, PipelineGraphNode node) {
		for (String dep : node.getDependencies()) {
			if (!state.isSettled(dep)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Decide whether this node should be skipped instead of dispatched.
	 *
	 * @return the skip result, or null when the node should run
	 */
	private NodeTaskResult evaluateSkip(ItemState state, PipelineGraphNode node) {
		if (graph.isDryRun()) {
			return NodeTaskResult.skipped(node.getId(), "dry-run");
		}

		for (String dep : node.getDependencies()) {
			NodeTaskResult depResult = state.getResults().get(dep);

			// Blocking is a property of the dependent node, not of the dependency.
			// A non-blocking node runs anyway and sees the failure in its inputs.
			if (depResult.getState() == NodeState.FAILED && node.isBlocking()) {
				return NodeTaskResult.skipped(node.getId(), "Dependency " + dep + " failed");
			}

			FilterBranch branch = node.branchFor(dep);
			if (branch != FilterBranch.ANY && !branch.admits(depResult.getFilterPassed())) {
				return NodeTaskResult.skipped(node.getId(),
					"Filter branch " + branch + " not taken on dependency " + dep);
			}
		}
		return null;
	}

	/**
	 * @return true when the node was settled synchronously (dispatch was refused),
	 *         false when a result is expected to arrive later
	 */
	private boolean dispatch(ItemState state, PipelineGraphNode node) {
		UUID taskUuid = UUID.randomUUID();
		NodeTask task = new NodeTask(taskUuid, runUuid, state.getItemId(), node.getId(), node.getKind(),
			state.getMedia(), node.getOptions(), collectUpstreamOutputs(state, node));

		state.markInFlight(node.getId(), taskUuid);
		boolean accepted;
		try {
			accepted = dispatcher.dispatch(task);
		} catch (Exception e) {
			log.error("Dispatch of {} threw", task, e);
			accepted = false;
		}

		if (!accepted) {
			// No worker could take it. Fail the node rather than leaving the run
			// stalled forever waiting for a result that will never arrive.
			state.record(NodeTaskResult.failed(taskUuid, node.getId(), 0,
				"No worker available for node kind '" + node.getKind() + "'"));
			return true;
		}
		return false;
	}

	/**
	 * Collect the outputs of this node's dependencies.
	 *
	 * <p>Phase 1 sends every dependency's outputs. That is fine for hashes and is
	 * known not to survive large values - narrowing this to the inputs a node
	 * actually declares is tracked as Phase 2 work.</p>
	 */
	private Map<String, Map<String, Object>> collectUpstreamOutputs(ItemState state, PipelineGraphNode node) {
		Map<String, Map<String, Object>> upstream = new LinkedHashMap<>();
		for (String dep : node.getDependencies()) {
			NodeTaskResult depResult = state.getResults().get(dep);
			if (depResult != null && !depResult.getOutputs().isEmpty()) {
				upstream.put(dep, depResult.getOutputs());
			}
		}
		return upstream;
	}

	private void checkComplete() {
		if (runComplete || !sourceComplete) {
			return;
		}
		for (ItemState state : items.values()) {
			if (!state.isComplete(graph.size())) {
				return;
			}
		}
		runComplete = true;
		RunSummary summary = buildSummary();
		log.info("Run {} complete: {}", runUuid, summary);
		for (Consumer<RunSummary> listener : completionListeners) {
			try {
				listener.accept(summary);
			} catch (Exception e) {
				log.error("Run completion listener threw", e);
			}
		}
	}

	private RunSummary buildSummary() {
		long success = 0;
		long failure = 0;
		long skipped = 0;
		for (ItemState state : items.values()) {
			switch (state.outcome()) {
				case SUCCESS:
					success++;
					break;
				case FAILURE:
					failure++;
					break;
				default:
					skipped++;
					break;
			}
		}
		return new RunSummary(items.size(), success, failure, skipped, System.currentTimeMillis() - startedAt);
	}

	private void requireStarted() {
		if (!started) {
			throw new IllegalStateException("Run not started");
		}
	}
}
