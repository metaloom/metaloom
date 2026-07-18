package io.metaloom.loom.pipeline.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * Per-media-item execution state: which nodes have finished, and how.
 *
 * <p>In Phase 1 this lives in memory, so a Loom restart loses in-flight runs. That
 * is a deliberate, documented limitation - Phase 2 moves it to Postgres.</p>
 *
 * <p>Not thread-safe on its own; {@link PipelineRunEngine} serialises all access.</p>
 */
public class ItemState {

	private final String itemId;
	private final MediaRef media;
	private final Map<String, NodeTaskResult> results = new LinkedHashMap<>();
	private final Map<String, UUID> inFlight = new LinkedHashMap<>();
	private final Map<String, Integer> attempts = new LinkedHashMap<>();

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

	/** @return results by node id, in completion order */
	public Map<String, NodeTaskResult> getResults() {
		return results;
	}

	void record(NodeTaskResult result) {
		results.put(result.getNodeId(), result);
		inFlight.remove(result.getNodeId());
	}

	void markInFlight(String nodeId, UUID taskUuid) {
		inFlight.put(nodeId, taskUuid);
	}

	/**
	 * Give up on the in-flight task for a node without settling it, so it can be
	 * dispatched again.
	 */
	void clearInFlight(String nodeId) {
		inFlight.remove(nodeId);
	}

	/**
	 * @param nodeId the node
	 * @return how many times it has been handed to a worker
	 */
	int attemptsFor(String nodeId) {
		return attempts.getOrDefault(nodeId, 0);
	}

	/**
	 * @param nodeId the node
	 * @return the new attempt count
	 */
	int recordAttempt(String nodeId) {
		return attempts.merge(nodeId, 1, Integer::sum);
	}

	boolean isSettled(String nodeId) {
		return results.containsKey(nodeId);
	}

	boolean isInFlight(String nodeId) {
		return inFlight.containsKey(nodeId);
	}

	/**
	 * @param taskUuid the task
	 * @return the node id that task belongs to, or null when it is unknown or already settled
	 */
	String nodeIdForTask(UUID taskUuid) {
		return inFlight.entrySet().stream()
			.filter(e -> e.getValue().equals(taskUuid))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(null);
	}

	/**
	 * @param totalNodes number of nodes in the graph
	 * @return true when every node has a terminal result
	 */
	boolean isComplete(int totalNodes) {
		return results.size() == totalNodes;
	}

	/**
	 * Classify the item for run-level counters.
	 *
	 * <p>Mirrors the semantics the existing Cortex executor reports: any failed node
	 * makes the item a failure; an item where nothing actually ran counts as skipped
	 * rather than as a success, so a dry run or a fully filtered item is not reported
	 * as work done.</p>
	 *
	 * @return the outcome
	 */
	ItemOutcome outcome() {
		boolean anyFailed = results.values().stream().anyMatch(r -> r.getState() == NodeState.FAILED);
		if (anyFailed) {
			return ItemOutcome.FAILURE;
		}
		boolean anyCompleted = results.values().stream().anyMatch(r -> r.getState() == NodeState.COMPLETED);
		return anyCompleted ? ItemOutcome.SUCCESS : ItemOutcome.SKIPPED;
	}

	/** Per-item classification used for run counters. */
	public enum ItemOutcome {
		SUCCESS, FAILURE, SKIPPED
	}
}
