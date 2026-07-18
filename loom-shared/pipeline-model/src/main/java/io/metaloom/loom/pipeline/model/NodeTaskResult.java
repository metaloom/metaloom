package io.metaloom.loom.pipeline.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a single {@link NodeTask}.
 *
 * <p>A failure is a <em>value</em>, not an exception: a node that throws is
 * reported as {@link NodeState#FAILED} so that one bad item cannot take down a
 * run. The engine decides what that means for downstream nodes.</p>
 */
public class NodeTaskResult {

	private final UUID taskUuid;
	private final String nodeId;
	private final NodeState state;
	private final long durationMs;
	private final String message;
	private final Map<String, Object> outputs;

	public NodeTaskResult(UUID taskUuid, String nodeId, NodeState state, long durationMs,
		String message, Map<String, Object> outputs) {
		this.taskUuid = taskUuid;
		this.nodeId = Objects.requireNonNull(nodeId, "A node id must be set");
		this.state = Objects.requireNonNull(state, "A node state must be set");
		this.durationMs = durationMs;
		this.message = message;
		this.outputs = outputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
	}

	public static NodeTaskResult completed(UUID taskUuid, String nodeId, long durationMs, Map<String, Object> outputs) {
		return new NodeTaskResult(taskUuid, nodeId, NodeState.COMPLETED, durationMs, null, outputs);
	}

	public static NodeTaskResult failed(UUID taskUuid, String nodeId, long durationMs, String message) {
		return new NodeTaskResult(taskUuid, nodeId, NodeState.FAILED, durationMs, message, null);
	}

	public static NodeTaskResult skipped(String nodeId, String reason) {
		return new NodeTaskResult(null, nodeId, NodeState.SKIPPED, 0, reason, null);
	}

	/**
	 * @return the task this result answers, or null for a result the engine synthesised
	 *         without dispatching (a skip, or the source node)
	 */
	public UUID getTaskUuid() {
		return taskUuid;
	}

	public String getNodeId() {
		return nodeId;
	}

	public NodeState getState() {
		return state;
	}

	public long getDurationMs() {
		return durationMs;
	}

	/** @return failure detail or skip reason, null on success */
	public String getMessage() {
		return message;
	}

	/** @return node outputs, never null */
	public Map<String, Object> getOutputs() {
		return outputs;
	}

	/**
	 * Read the {@code filter_passed} verdict, if this node produced one.
	 *
	 * @return the verdict, or null when this node is not a filter
	 */
	public Boolean getFilterPassed() {
		Object value = outputs.get(FilterBranch.FILTER_PASSED);
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		// Persistent caches on the Cortex side stringify values, so a boolean can
		// arrive as "true"/"false". Tolerate that rather than silently mis-routing.
		if (value instanceof String) {
			return Boolean.parseBoolean((String) value);
		}
		return null;
	}

	@Override
	public String toString() {
		return "NodeTaskResult[" + nodeId + " " + state + (message != null ? ": " + message : "") + "]";
	}
}
