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
	private final RunStateStore store;

	private final Map<String, ItemState> items = new LinkedHashMap<>();
	private final List<Consumer<RunSummary>> completionListeners = new ArrayList<>();

	private boolean started;
	private boolean sourceComplete;
	private boolean runComplete;
	private long startedAt;
	private long itemSequence;

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher, UUID runUuid, RunStateStore store) {
		this.graph = graph;
		this.dispatcher = dispatcher;
		this.runUuid = runUuid;
		this.store = store == null ? RunStateStore.NOOP : store;
	}

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher, UUID runUuid) {
		this(graph, dispatcher, runUuid, RunStateStore.NOOP);
	}

	public PipelineRunEngine(PipelineGraph graph, NodeDispatcher dispatcher) {
		this(graph, dispatcher, null, RunStateStore.NOOP);
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

		long itemSeq = ++itemSequence;
		// Identity comes from the store, not from a counter, so a run recovered after a
		// restart can still match an arriving result to its item.
		String itemId = store.itemDiscovered(runUuid, itemSeq, media).toString();
		ItemState state = new ItemState(itemId, media);
		items.put(itemId, state);

		PipelineGraphNode source = graph.getSourceNode();
		Map<String, Object> outputs = new LinkedHashMap<>();
		outputs.put(OUTPUT_PATH, media.getPath());
		outputs.put(OUTPUT_SOURCE, source.getKind());

		NodeTaskResult sourceResult = graph.isDryRun()
			? NodeTaskResult.skipped(source.getId(), "dry-run")
			: NodeTaskResult.completed(null, source.getId(), 0, outputs);
		record(state, sourceResult);

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
		record(state, result);
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
					record(state, skip);
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
		store.taskDispatched(itemUuid(state), task);
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
			record(state, NodeTaskResult.failed(taskUuid, node.getId(), 0,
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

	/**
	 * Settle a node and tell the store about it.
	 *
	 * <p>Every path that settles a node goes through here - a completed dispatch, a
	 * skip, and a refused dispatch alike. Recording in only some of them is how a
	 * recovered run ends up re-running work it already did.</p>
	 */
	private void record(ItemState state, NodeTaskResult result) {
		state.record(result);
		UUID itemUuid = itemUuid(state);
		store.taskSettled(itemUuid, result);
		if (state.isComplete(graph.size())) {
			store.itemSettled(itemUuid, state.outcome());
		}
	}

	/**
	 * @param state the item
	 * @return its store-assigned id, or null when the id is not a UUID (which only
	 *         happens with a store that does not persist)
	 */
	private UUID itemUuid(ItemState state) {
		try {
			return UUID.fromString(state.getItemId());
		} catch (IllegalArgumentException e) {
			return null;
		}
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
		// A batching store must drain here, or the tail of the run - the part that
		// says how it ended - is exactly what gets lost.
		store.flush();
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
