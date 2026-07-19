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

	/**
	 * How many node tasks one run may have outstanding at once.
	 *
	 * <p>Without a ceiling a fast source and slow nodes produce unbounded outstanding
	 * work: a 100 000 item scan would dispatch every ready node immediately, and one
	 * large run would consume the entire worker fleet.</p>
	 */
	public static final int DEFAULT_MAX_IN_FLIGHT = 256;

	/** First retry waits this long; each further attempt doubles it. */
	public static final long DEFAULT_RETRY_BASE_DELAY_MS = 1_000;

	/** Ceiling on the backoff, so a high attempt count cannot park a node for hours. */
	public static final long MAX_RETRY_DELAY_MS = 60_000;

	private static final String OUTPUT_PATH = "path";
	private static final String OUTPUT_SOURCE = "source";

	private final PipelineGraph graph;
	private final NodeDispatcher dispatcher;
	private final UUID runUuid;
	private final RunStateStore store;
	private RetryScheduler retryScheduler = RetryScheduler.IMMEDIATE;
	private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;
	private int inFlightCount;
	private long retryBaseDelayMs = DEFAULT_RETRY_BASE_DELAY_MS;

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
	 * Rebuild an item from persisted state, without dispatching anything.
	 *
	 * <p>Used by recovery to put the engine back where it was before a restart. The
	 * settled results are adopted as-is: a node that already ran must <em>not</em> run
	 * again, which is the entire point of having persisted them.</p>
	 *
	 * <p>Nothing is dispatched here. Call {@link #resume()} once every item has been
	 * restored, so the engine sees a complete picture before it starts making
	 * decisions - otherwise it could complete the run on the first restored item,
	 * before the rest have been read back.</p>
	 *
	 * @param itemId  the item's persisted id
	 * @param media   the item
	 * @param settled results already recorded, by node id
	 * @param attempts how many times each node had been dispatched, by node id
	 */
	public synchronized void restoreItem(String itemId, MediaRef media, Map<String, NodeTaskResult> settled,
		Map<String, Integer> attempts) {
		requireStarted();
		ItemState state = new ItemState(itemId, media);
		if (settled != null) {
			for (NodeTaskResult result : settled.values()) {
				state.record(result);
			}
		}
		if (attempts != null) {
			// Carrying the attempt count over is what stops a restart from silently
			// resetting the retry budget and letting a poison item run forever.
			attempts.forEach((nodeId, count) -> {
				for (int i = 0; i < count; i++) {
					state.recordAttempt(nodeId);
				}
			});
		}
		items.put(itemId, state);
	}

	/**
	 * Resume a restored run: dispatch whatever is now ready.
	 *
	 * <p>A node that was in flight when the process died is simply unsettled here, so
	 * it becomes ready again and is dispatched. Its lease row is stale, which the
	 * reaper tidies up.</p>
	 *
	 * @param sourceWasComplete whether the source had finished enumerating before the
	 *                          restart; a run whose source was still scanning cannot
	 *                          be completed faithfully, because the files it had not
	 *                          reached yet were never recorded
	 */
	public synchronized void resume(boolean sourceWasComplete) {
		requireStarted();
		sourceComplete = sourceWasComplete;
		log.info("Resuming run {} with {} restored item(s), source {}", runUuid, items.size(),
			sourceWasComplete ? "complete" : "INCOMPLETE - remaining media were never recorded");
		for (ItemState state : items.values()) {
			advance(state);
		}
		checkComplete();
	}

	/**
	 * Called once the source has finished enumerating.
	 *
	 * @param totalCount number of items the source produced, for reconciliation
	 */
	public synchronized void onSourceComplete(long totalCount) {
		requireStarted();
		sourceComplete = true;
		store.sourceCompleted(runUuid, totalCount);
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

		// A failure is not automatically final. If the node asked to be retried and has
		// attempts left, hand it back rather than settling it - otherwise `retryFailed`
		// would remain the decoration it has always been.
		if (result.getState() == NodeState.FAILED && shouldRetry(state, result.getNodeId())) {
			scheduleRetry(state, result.getNodeId(), describe(result));
			// The failed attempt released its slot; someone else may be waiting for it.
			pumpDeferred();
			return;
		}

		record(state, result);
		advance(state);
		// Capacity may have just freed up for work deferred on other items.
		pumpDeferred();
		checkComplete();
	}

	/**
	 * Called when a dispatched task will never report back - typically because its
	 * lease expired and the worker holding it is presumed dead.
	 *
	 * <p>Re-dispatches when attempts remain, and dead-letters otherwise. A lost task
	 * that is neither retried nor settled stalls its item forever, so this method
	 * must always do one of the two.</p>
	 *
	 * @param itemId the item
	 * @param nodeId the node whose task was lost
	 * @param reason why it was reclaimed, for the dead-letter record
	 */
	public synchronized void onNodeTaskLost(String itemId, String nodeId, String reason) {
		requireStarted();
		ItemState state = items.get(itemId);
		if (state == null) {
			log.warn("Lost task for unknown item '{}' in run {} - ignoring", itemId, runUuid);
			return;
		}
		if (state.isSettled(nodeId)) {
			// The result won the race against the reaper. Nothing to reclaim.
			log.debug("Node '{}' on item '{}' already settled - ignoring reclaim", nodeId, itemId);
			return;
		}
		if (!state.isInFlight(nodeId)) {
			log.debug("Node '{}' on item '{}' is not in flight - ignoring reclaim", nodeId, itemId);
			return;
		}

		releaseInFlight(state, nodeId);
		if (shouldRetry(state, nodeId)) {
			scheduleRetry(state, nodeId, reason);
			return;
		}

		record(state, NodeTaskResult.failed(null, nodeId, 0,
			"Dead-lettered after " + state.attemptsFor(nodeId) + " attempt(s): " + reason));
		advance(state);
		// The dead-lettered task released its slot; hand it to whoever is waiting.
		pumpDeferred();
		checkComplete();
	}

	/**
	 * @return true when the node may be attempted again
	 */
	private boolean shouldRetry(ItemState state, String nodeId) {
		PipelineGraphNode node = graph.getNode(nodeId);
		if (node == null) {
			return false;
		}
		return state.attemptsFor(nodeId) < node.getMaxAttempts();
	}

	/**
	 * Hand a node back for another attempt after a backoff.
	 *
	 * <p>The retry runs outside this method's synchronised block when the scheduler
	 * defers it, and re-enters through {@link #retryNow}, which re-checks state - by
	 * the time a delayed retry fires the run may have completed or the node may have
	 * settled some other way.</p>
	 */
	private void scheduleRetry(ItemState state, String nodeId, String reason) {
		releaseInFlight(state, nodeId);
		state.markAwaitingRetry(nodeId);
		int attempt = state.attemptsFor(nodeId);
		long delay = backoffFor(attempt);
		log.info("Retrying node '{}' on item '{}' (attempt {} of {}) in {}ms after: {}",
			nodeId, state.getItemId(), attempt + 1, graph.getNode(nodeId).getMaxAttempts(), delay, reason);

		String itemId = state.getItemId();
		retryScheduler.schedule(delay, () -> retryNow(itemId, nodeId));
	}

	/**
	 * Re-enter the engine to dispatch a retry.
	 *
	 * @param itemId the item
	 * @param nodeId the node to attempt again
	 */
	private synchronized void retryNow(String itemId, String nodeId) {
		ItemState state = items.get(itemId);
		if (state == null) {
			return;
		}
		state.clearAwaitingRetry(nodeId);
		if (state.isSettled(nodeId) || state.isInFlight(nodeId)) {
			return;
		}
		advance(state);
		checkComplete();
	}

	/**
	 * Exponential backoff, capped.
	 *
	 * @param attempt attempts already made
	 * @return how long to wait before the next one
	 */
	long backoffFor(int attempt) {
		if (attempt <= 0) {
			return 0;
		}
		long delay = retryBaseDelayMs << Math.min(attempt - 1, 20);
		return Math.min(delay, MAX_RETRY_DELAY_MS);
	}

	private static String describe(NodeTaskResult result) {
		return result.getMessage() == null ? "node failed" : result.getMessage();
	}

	/**
	 * Replace the retry scheduler, e.g. with one backed by a Vert.x timer.
	 *
	 * @param scheduler the scheduler; null restores the immediate default
	 */
	public synchronized void setRetryScheduler(RetryScheduler scheduler) {
		this.retryScheduler = scheduler == null ? RetryScheduler.IMMEDIATE : scheduler;
	}

	/**
	 * @param baseDelayMs delay before the first retry; each further attempt doubles it
	 */
	public synchronized void setRetryBaseDelayMs(long baseDelayMs) {
		this.retryBaseDelayMs = Math.max(0, baseDelayMs);
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
				if (state.isSettled(nodeId) || state.isInFlight(nodeId) || state.isAwaitingRetry(nodeId)) {
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

				if (atCapacity()) {
					// Leave the node unsettled and undispatched. It stays ready, and
					// pumpDeferred() picks it up as soon as something finishes.
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
		state.recordAttempt(node.getId());
		inFlightCount++;
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
		if (state.isInFlight(result.getNodeId())) {
			inFlightCount = Math.max(0, inFlightCount - 1);
		}
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

	/**
	 * Stop counting a node against the in-flight ceiling.
	 *
	 * <p>Every path that ends a dispatched task without settling it - a retry, a
	 * reclaim - must come through here. Clearing the in-flight marker without
	 * decrementing leaks a slot, and enough leaked slots wedge the run permanently at
	 * capacity with nothing outstanding.</p>
	 */
	private void releaseInFlight(ItemState state, String nodeId) {
		if (state.isInFlight(nodeId)) {
			inFlightCount = Math.max(0, inFlightCount - 1);
			state.clearInFlight(nodeId);
		}
	}

	/** @return true when this run already has as much outstanding work as it may */
	private boolean atCapacity() {
		return maxInFlight > 0 && inFlightCount >= maxInFlight;
	}

	/**
	 * Give every item a chance to dispatch work that was held back by the cap.
	 *
	 * <p>A node deferred on item B is not reconsidered when item A finishes unless
	 * something sweeps the whole set - without this, deferred work would only resume
	 * when its own item happened to progress, which it cannot do while it is blocked.</p>
	 */
	private void pumpDeferred() {
		if (atCapacity()) {
			return;
		}
		for (ItemState state : items.values()) {
			if (atCapacity()) {
				return;
			}
			if (!state.isComplete(graph.size())) {
				advance(state);
			}
		}
	}

	/**
	 * @return true when the run is holding as many tasks as it is allowed to
	 */
	public synchronized boolean isAtCapacity() {
		return atCapacity();
	}

	/** @return outstanding dispatched tasks */
	public synchronized int getInFlightCount() {
		return inFlightCount;
	}

	/**
	 * @param maxInFlight ceiling on outstanding tasks; 0 or less means unlimited
	 */
	public synchronized void setMaxInFlight(int maxInFlight) {
		this.maxInFlight = maxInFlight;
		pumpDeferred();
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
