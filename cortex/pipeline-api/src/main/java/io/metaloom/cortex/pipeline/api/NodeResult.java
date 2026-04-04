package io.metaloom.cortex.pipeline.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of processing a single media item through a pipeline node.
 * <p>
 * Nodes can attach arbitrary output data via {@link #getOutput()} so that downstream
 * dependent nodes can read it from their {@code upstreamResults} map.
 * <p>
 * Example: an LLM node that extracts an image description stores the text under
 * key {@code "description"}, and a subsequent LLM node reads it to generate a summary.
 */
public class NodeResult {

	private final NodeState state;
	private final String nodeId;
	private final long durationMs;
	private final String message;
	private final Map<String, Object> output;

	public NodeResult(String nodeId, NodeState state, long durationMs, String message) {
		this(nodeId, state, durationMs, message, Collections.emptyMap());
	}

	public NodeResult(String nodeId, NodeState state, long durationMs, String message, Map<String, Object> output) {
		this.nodeId = nodeId;
		this.state = state;
		this.durationMs = durationMs;
		this.message = message;
		this.output = output != null ? Collections.unmodifiableMap(new HashMap<>(output)) : Collections.emptyMap();
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

	/**
	 * Typed output data produced by this node. Downstream nodes can read this
	 * from their {@code upstreamResults} parameter.
	 * <p>
	 * Common keys: {@code "description"}, {@code "transcript"}, {@code "tags"}, {@code "embedding"}.
	 */
	public Map<String, Object> getOutput() {
		return output;
	}

	/**
	 * Convenience accessor for a single output value.
	 */
	@SuppressWarnings("unchecked")
	public <T> T getOutput(String key) {
		return (T) output.get(key);
	}

	public static NodeResult success(String nodeId, long durationMs) {
		return new NodeResult(nodeId, NodeState.COMPLETED, durationMs, null);
	}

	public static NodeResult success(String nodeId, long durationMs, Map<String, Object> output) {
		return new NodeResult(nodeId, NodeState.COMPLETED, durationMs, null, output);
	}

	public static NodeResult failed(String nodeId, long durationMs, String message) {
		return new NodeResult(nodeId, NodeState.FAILED, durationMs, message);
	}

	public static NodeResult skipped(String nodeId, String reason) {
		return new NodeResult(nodeId, NodeState.SKIPPED, 0, reason);
	}

	@Override
	public String toString() {
		return nodeId + " [" + state + "] " + durationMs + "ms" + (message != null ? " - " + message : "")
				+ (output.isEmpty() ? "" : " output=" + output.keySet());
	}
}
