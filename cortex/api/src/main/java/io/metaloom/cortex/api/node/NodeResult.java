package io.metaloom.cortex.api.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of processing a single media item by a node.
 *
 * <p>This is the <em>single</em> node result type shared by both the Cortex-level
 * node API (produced by {@code NodeContext.next()/abort()} inside a
 * {@code CortexNode}) and the pipeline-level API ({@code PipelineNode.process}).
 * It carries the terminal {@link ResultState}, an optional pipeline {@code nodeId}
 * (null when produced outside a DAG, stamped later via {@link #withNode}), timing,
 * an optional message (skip reason / failure cause), and the port outputs.</p>
 *
 * <p>Outputs are keyed by <strong>output port id</strong> and each keeps the
 * {@link OutputPort} that declared it. The boundary needs the declared content type
 * to coerce against and the cardinality to decide how to stamp origins, and carrying
 * the port with the values is what stops it from having to look either up in a
 * descriptor the Cortex tree cannot see.</p>
 */
public class NodeResult {

	private final String nodeId;
	private final ResultState state;
	private final long durationMs;
	private final String message;
	private final Map<String, PortOutput> outputs;

	/**
	 * Canonical constructor.
	 *
	 * @param nodeId     the pipeline node id, or {@code null} when the result is produced outside a DAG
	 * @param state      the terminal result state
	 * @param durationMs elapsed processing time in milliseconds
	 * @param message    skip reason or failure cause, or {@code null}
	 * @param outputs    the port outputs (may be {@code null}, treated as empty)
	 */
	public NodeResult(String nodeId, ResultState state, long durationMs, String message, Map<String, PortOutput> outputs) {
		this.nodeId = nodeId;
		this.state = state;
		this.durationMs = durationMs;
		this.message = message;
		this.outputs = outputs != null ? Collections.unmodifiableMap(new LinkedHashMap<>(outputs)) : Collections.emptyMap();
	}

	public NodeResult(ResultState state) {
		this(null, state, 0, null, Collections.emptyMap());
	}

	public NodeResult(ResultState state, Map<String, PortOutput> outputs) {
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
	 * The port outputs produced by the node, keyed by output port id.
	 */
	public Map<String, PortOutput> getOutputs() {
		return outputs;
	}

	/**
	 * Type-safe accessor for the value of a {@code ONE} output port.
	 *
	 * @return the value, or {@code null} when the node emitted nothing on that port
	 */
	public <T> T get(OutputPort<T> port) {
		PortOutput output = outputs.get(port.id());
		return output == null ? null : port.valueType().cast(output.single());
	}

	/**
	 * Type-safe accessor for the elements of a {@code MANY} output port, in emission order.
	 */
	public <T> List<T> elements(OutputPort<T> port) {
		PortOutput output = outputs.get(port.id());
		if (output == null) {
			return List.of();
		}
		return output.values().stream().map(port.valueType()::cast).toList();
	}

	/**
	 * Whether the node emitted anything on the given port.
	 */
	public boolean has(OutputPort<?> port) {
		return outputs.containsKey(port.id());
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

	public static NodeResult success(Map<String, PortOutput> outputs) {
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

	public static NodeResult success(String nodeId, long durationMs, Map<String, PortOutput> outputs) {
		return new NodeResult(nodeId, ResultState.SUCCESS, durationMs, null, outputs);
	}

	public static NodeResult failed(String nodeId, long durationMs, String message) {
		return new NodeResult(nodeId, ResultState.FAILED, durationMs, message, Collections.emptyMap());
	}

	/**
	 * A failure that still carries whatever the node managed to emit before it broke.
	 */
	public static NodeResult failed(String nodeId, long durationMs, String message, Map<String, PortOutput> outputs) {
		return new NodeResult(nodeId, ResultState.FAILED, durationMs, message, outputs);
	}

	public static NodeResult skipped(String nodeId, String reason) {
		return new NodeResult(nodeId, ResultState.SKIPPED, 0, reason, Collections.emptyMap());
	}

	/**
	 * A skip that still carries whatever the node emitted before deciding to stop.
	 */
	public static NodeResult skipped(String nodeId, String reason, Map<String, PortOutput> outputs) {
		return new NodeResult(nodeId, ResultState.SKIPPED, 0, reason, outputs);
	}

	@Override
	public String toString() {
		return (nodeId != null ? nodeId + " " : "") + "[" + state + "]"
			+ (durationMs > 0 ? " " + durationMs + "ms" : "")
			+ (message != null ? " - " + message : "")
			+ (!outputs.isEmpty() ? " outputs=" + outputs.keySet() : "");
	}
}
