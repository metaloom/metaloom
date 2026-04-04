package io.metaloom.cortex.pipeline.api;

/**
 * Result of processing a single media item through a pipeline node.
 */
public class NodeResult {

	private final NodeState state;
	private final String nodeId;
	private final long durationMs;
	private final String message;

	public NodeResult(String nodeId, NodeState state, long durationMs, String message) {
		this.nodeId = nodeId;
		this.state = state;
		this.durationMs = durationMs;
		this.message = message;
	}

	public NodeState getState() {
		return state;
	}

	public String getNodeId() {
		return nodeId;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public String getMessage() {
		return message;
	}

	public static NodeResult success(String nodeId, long durationMs) {
		return new NodeResult(nodeId, NodeState.COMPLETED, durationMs, null);
	}

	public static NodeResult failed(String nodeId, long durationMs, String message) {
		return new NodeResult(nodeId, NodeState.FAILED, durationMs, message);
	}

	public static NodeResult skipped(String nodeId, String reason) {
		return new NodeResult(nodeId, NodeState.SKIPPED, 0, reason);
	}

	@Override
	public String toString() {
		return nodeId + " [" + state + "] " + durationMs + "ms" + (message != null ? " - " + message : "");
	}
}
