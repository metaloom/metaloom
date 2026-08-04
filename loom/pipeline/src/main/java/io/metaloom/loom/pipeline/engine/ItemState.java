package io.metaloom.loom.pipeline.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.pipeline.graph.ExecutionMode;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * Per-media-item execution state: which nodes have finished, and how.
 *
 * <p>An item is also an <strong>origin</strong>. When a node fans out — one image into N detected
 * faces — the elements stay inside this item rather than becoming child items, and every element
 * carries this item's id. That is what lets a later node recombine two branches per source asset
 * without any lineage bookkeeping, and it is why fan-out needed no change to
 * {@code pipeline_run_item}.</p>
 *
 * <p>Each node therefore holds a {@link NodeExecState} rather than a single result. The node-level
 * methods here answer over the whole node ("is it settled?" means "have all its elements settled?"),
 * so scheduling code that thinks in nodes keeps working unchanged; the seq-aware overloads are for
 * the dispatch path, which thinks in elements.</p>
 *
 * <p>Not thread-safe on its own; {@link PipelineRunEngine} serialises all access.</p>
 */
public class ItemState {

	private final String itemId;
	private final MediaRef media;
	private final Map<String, NodeExecState> execs = new LinkedHashMap<>();

	ItemState(String itemId, MediaRef media) {
		this.itemId = itemId;
		this.media = media;
	}

	public String getItemId() {
		return itemId;
	}

	public MediaRef getMedia() {
		return media;
	}

	/**
	 * The execution state of one node, created on first use.
	 *
	 * @param mode how often the node runs; only honoured when the state is created
	 */
	NodeExecState exec(String nodeId, ExecutionMode mode) {
		return execs.computeIfAbsent(nodeId, k -> new NodeExecState(mode));
	}

	/**
	 * @return the execution state, or null when this node has not been touched yet
	 */
	public NodeExecState exec(String nodeId) {
		return execs.get(nodeId);
	}

	/** @return every node's execution state, in first-touch order */
	public Map<String, NodeExecState> getExecs() {
		return execs;
	}

	/**
	 * Representative results by node id, for callers that reason about whole nodes — branch
	 * evaluation, result reuse, progress reporting.
	 *
	 * @return one result per settled node
	 */
	public Map<String, NodeTaskResult> getResults() {
		Map<String, NodeTaskResult> out = new LinkedHashMap<>();
		for (Map.Entry<String, NodeExecState> entry : execs.entrySet()) {
			NodeTaskResult representative = entry.getValue().representative();
			if (representative != null) {
				out.put(entry.getKey(), representative);
			}
		}
		return out;
	}

	void record(NodeTaskResult result) {
		record(result.getNodeId(), result.getElementSeq(), result);
	}

	void record(String nodeId, int seq, NodeTaskResult result) {
		exec(nodeId, ExecutionMode.SINGLE).record(seq, result);
	}

	void markInFlight(String nodeId, UUID taskUuid) {
		markInFlight(nodeId, 0, taskUuid);
	}

	void markInFlight(String nodeId, int seq, UUID taskUuid) {
		exec(nodeId, ExecutionMode.SINGLE).markInFlight(seq, taskUuid);
	}

	/**
	 * Give up on the in-flight task for a node without settling it, so it can be
	 * dispatched again.
	 */
	void clearInFlight(String nodeId) {
		clearInFlight(nodeId, 0);
	}

	void clearInFlight(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		if (state != null) {
			state.clearInFlight(seq);
		}
	}

	int attemptsFor(String nodeId) {
		return attemptsFor(nodeId, 0);
	}

	int attemptsFor(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		return state == null ? 0 : state.attemptsFor(seq);
	}

	int recordAttempt(String nodeId) {
		return recordAttempt(nodeId, 0);
	}

	int recordAttempt(String nodeId, int seq) {
		return exec(nodeId, ExecutionMode.SINGLE).recordAttempt(seq);
	}

	/**
	 * Refund the attempt a dispatch consumed, because the worker handed the task back
	 * without running it.
	 *
	 * @return true when the attempt was refunded, false once the execution has been
	 *         returned too often to keep trusting it
	 */
	boolean refundAttempt(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		return state != null && state.refundAttempt(seq);
	}

	/**
	 * Mark an execution as waiting out its retry backoff.
	 *
	 * <p>Such an execution is neither settled nor in flight, so without this marker anything
	 * that sweeps for ready work would dispatch it immediately and the backoff would
	 * never actually happen.</p>
	 */
	void markAwaitingRetry(String nodeId) {
		markAwaitingRetry(nodeId, 0);
	}

	void markAwaitingRetry(String nodeId, int seq) {
		exec(nodeId, ExecutionMode.SINGLE).markAwaitingRetry(seq);
	}

	void clearAwaitingRetry(String nodeId) {
		clearAwaitingRetry(nodeId, 0);
	}

	void clearAwaitingRetry(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		if (state != null) {
			state.clearAwaitingRetry(seq);
		}
	}

	boolean isAwaitingRetry(String nodeId) {
		return isAwaitingRetry(nodeId, 0);
	}

	boolean isAwaitingRetry(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		return state != null && state.isAwaitingRetry(seq);
	}

	/**
	 * Whether every execution of this node has reached a terminal state.
	 *
	 * <p><strong>This is the gather barrier.</strong> For a node that ran once per element it means
	 * all its sibling elements have finished, so a downstream node waiting on its dependencies is
	 * automatically waiting for the whole fanned-out branch.</p>
	 */
	boolean isSettled(String nodeId) {
		NodeExecState state = execs.get(nodeId);
		return state != null && state.isSettled();
	}

	boolean isSettled(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		return state != null && state.isSettled(seq);
	}

	/**
	 * Whether a breakpoint is withholding any execution of this node.
	 *
	 * <p>
	 * Answered over the whole node on purpose, so it composes with {@link #isSettled(String)} — the
	 * gather barrier. A dependent of a fanned-out node consumes the <em>sequence</em>, so it may not
	 * start until every element of that sequence has been released, exactly as it may not start
	 * until every element has settled.
	 * </p>
	 */
	boolean isHeld(String nodeId) {
		NodeExecState state = execs.get(nodeId);
		return state != null && state.isHeld();
	}

	int heldCount() {
		return execs.values().stream().mapToInt(NodeExecState::heldCount).sum();
	}

	/**
	 * @return which attempt of this execution is current; 0 for a node that has only ever run once
	 */
	int generationFor(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		return state == null ? 0 : state.generationFor(seq);
	}

	/**
	 * Discard one settled execution so it can run again. See {@link NodeExecState#clearResult(int)}.
	 *
	 * @return the generation the next dispatch will carry, or 0 when the node was never touched
	 */
	int clearResult(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		return state == null ? 0 : state.clearResult(seq);
	}

	boolean isInFlight(String nodeId) {
		NodeExecState state = execs.get(nodeId);
		return state != null && state.hasInFlight();
	}

	boolean isInFlight(String nodeId, int seq) {
		NodeExecState state = execs.get(nodeId);
		return state != null && state.isInFlight(seq);
	}

	/**
	 * @param taskUuid the task
	 * @return the node id and element the task belongs to, or null when unknown or already settled
	 */
	TaskRef taskRef(UUID taskUuid) {
		for (Map.Entry<String, NodeExecState> entry : execs.entrySet()) {
			Integer seq = entry.getValue().seqForTask(taskUuid);
			if (seq != null) {
				return new TaskRef(entry.getKey(), seq);
			}
		}
		return null;
	}

	/**
	 * @param taskUuid the task
	 * @return the node id that task belongs to, or null when it is unknown or already settled
	 */
	String nodeIdForTask(UUID taskUuid) {
		TaskRef ref = taskRef(taskUuid);
		return ref == null ? null : ref.nodeId();
	}

	/**
	 * @param totalNodes number of nodes in the graph
	 * @return true when every node has settled every one of its executions
	 */
	boolean isComplete(int totalNodes) {
		if (execs.size() < totalNodes) {
			return false;
		}
		return execs.values().stream().allMatch(NodeExecState::isSettled);
	}

	/**
	 * Classify the item for run-level counters.
	 *
	 * <p>Mirrors the semantics the existing Cortex executor reports: any failed node
	 * makes the item a failure; an item where nothing actually ran counts as skipped
	 * rather than as a success, so a dry run or a fully filtered item is not reported
	 * as work done. A single failed element of a fanned-out node is enough to fail the
	 * item, matching the whole-node rule it replaces.</p>
	 *
	 * @return the outcome
	 */
	ItemOutcome outcome() {
		boolean anyFailed = execs.values().stream().anyMatch(e -> e.rollup() == NodeState.FAILED);
		if (anyFailed) {
			return ItemOutcome.FAILURE;
		}
		boolean anyCompleted = execs.values().stream().anyMatch(e -> e.rollup() == NodeState.COMPLETED);
		return anyCompleted ? ItemOutcome.SUCCESS : ItemOutcome.SKIPPED;
	}

	/** Which element of which node a dispatched task belongs to. */
	record TaskRef(String nodeId, int seq) {
	}

	/** Per-item classification used for run counters. */
	public enum ItemOutcome {
		SUCCESS, FAILURE, SKIPPED
	}
}
