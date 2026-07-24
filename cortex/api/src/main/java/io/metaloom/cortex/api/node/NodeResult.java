package io.metaloom.cortex.api.node;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of processing a single media item by a node.
 *
 * <p>This is the <em>single</em> node result type shared by both the Cortex-level
 * node API (produced by {@code NodeContext.next()/abort()} inside a
 * {@code CortexNode}) and the pipeline-level API ({@code PipelineNode.process}).
 * It carries the terminal {@link ResultState}, an optional pipeline {@code nodeId}
 * (null when produced outside a DAG, stamped later via {@link #withNode}), timing,
 * an optional message (skip reason / failure cause), and a typed output map.</p>
 *
 * <p>Output values are stored under {@link NodeOutputKey} keys which provide
 * type-safe access without casts. Downstream dependent nodes read them from their
 * {@code upstreamResults} / {@code upstreamOutputs}.</p>
 */
public class NodeResult {

	private final String nodeId;
	private final ResultState state;
	private final long durationMs;
	private final String message;
	private final Map<String, Object> outputs;

	/**
	 * Canonical constructor.
	 *
	 * @param nodeId     the pipeline node id, or {@code null} when the result is produced outside a DAG
	 * @param state      the terminal result state
	 * @param durationMs elapsed processing time in milliseconds
	 * @param message    skip reason or failure cause, or {@code null}
	 * @param outputs    the output map (may be {@code null}, treated as empty)
	 */
	public NodeResult(String nodeId, ResultState state, long durationMs, String message, Map<String, Object> outputs) {
		this.nodeId = nodeId;
		this.state = state;
		this.durationMs = durationMs;
		this.message = message;
		this.outputs = outputs != null ? Collections.unmodifiableMap(new HashMap<>(outputs)) : Collections.emptyMap();
	}

	public NodeResult(ResultState state) {
		this(null, state, 0, null, Collections.emptyMap());
	}

	public NodeResult(ResultState state, Map<String, Object> outputs) {
		this(null, state, 0, null, outputs);
	}

	public ResultState getState() {
		return state;
	}

	/**
	 * The pipeline node id, or {@code null} when the result was produced outside a DAG (e.g. directly by a {@code CortexNode} before the adapter stamps it).
	 */
	public String getNodeId() {
		return nodeId;
	}

	public long getDurationMs() {
		return durationMs;
	}

	/**
	 * Skip reason or failure cause, or {@code null} on success.
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Return the output map containing all key-value pairs produced by the node.
	 * This data is forwarded to downstream dependent nodes.
	 */
	public Map<String, Object> getOutputs() {
		return outputs;
	}

	/**
	 * Alias for {@link #getOutputs()} (pipeline-level naming).
	 */
	public Map<String, Object> getOutput() {
		return outputs;
	}

	/**
	 * Type-safe accessor for a single output value via a {@link NodeOutputKey}.
	 */
	@SuppressWarnings("unchecked")
	public <T> T get(NodeOutputKey<T> key) {
		return (T) outputs.get(key.key());
	}

	/**
	 * Check whether the output map contains a value for the given key.
	 */
	public boolean has(NodeOutputKey<?> key) {
		return outputs.containsKey(key.key());
	}

	/**
	 * Convenience accessor for a single output value by raw string key.
	 */
	@SuppressWarnings("unchecked")
	public <T> T getOutput(String key) {
		return (T) outputs.get(key);
	}

	/**
	 * Return a copy of this result stamped with a pipeline node id and duration, preserving state, message and outputs. Used by the adapter / runtime to
	 * attach DAG identity to a result minted by a {@code CortexNode} (which does not know its own pipeline id).
	 */
	public NodeResult withNode(String nodeId, long durationMs) {
		return new NodeResult(nodeId, state, durationMs, message, outputs);
	}

	// --- Node-level factories (no pipeline id) ---

	public static NodeResult success() {
		return new NodeResult(null, ResultState.SUCCESS, 0, null, Collections.emptyMap());
	}

	public static NodeResult success(Map<String, Object> outputs) {
		return new NodeResult(null, ResultState.SUCCESS, 0, null, outputs);
	}

	public static NodeResult failed() {
		return new NodeResult(null, ResultState.FAILED, 0, null, Collections.emptyMap());
	}

	public static NodeResult skipped() {
		return new NodeResult(null, ResultState.SKIPPED, 0, null, Collections.emptyMap());
	}

	// --- Pipeline-level factories (carry node id / duration / message) ---

	public static NodeResult success(String nodeId, long durationMs) {
		return new NodeResult(nodeId, ResultState.SUCCESS, durationMs, null, Collections.emptyMap());
	}

	public static NodeResult success(String nodeId, long durationMs, Map<String, Object> outputs) {
		return new NodeResult(nodeId, ResultState.SUCCESS, durationMs, null, outputs);
	}

	public static NodeResult failed(String nodeId, long durationMs, String message) {
		return new NodeResult(nodeId, ResultState.FAILED, durationMs, message, Collections.emptyMap());
	}

	public static NodeResult skipped(String nodeId, String reason) {
		return new NodeResult(nodeId, ResultState.SKIPPED, 0, reason, Collections.emptyMap());
	}

	@Override
	public String toString() {
		return (nodeId != null ? nodeId + " " : "") + "[" + state + "]"
			+ (durationMs > 0 ? " " + durationMs + "ms" : "")
			+ (message != null ? " - " + message : "")
			+ (!outputs.isEmpty() ? " outputs=" + outputs.keySet() : "");
	}
}
