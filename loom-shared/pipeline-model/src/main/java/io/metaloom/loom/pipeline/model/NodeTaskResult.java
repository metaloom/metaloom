package io.metaloom.loom.pipeline.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outcome of a single {@link NodeTask}.
 *
 * <p>A failure is a <em>value</em>, not an exception: a node that throws is
 * reported as {@link NodeState#FAILED} so that one bad item cannot take down a
 * run. The engine decides what that means for downstream nodes.</p>
 *
 * <p>Outputs are keyed by the producing node's <strong>output port ids</strong> and carry their
 * declared type and cardinality. A {@code MANY} port's payload is what makes the engine fan out:
 * its element count becomes the number of downstream per-element tasks.</p>
 *
 * <p>Unlike the previous model, outputs are <strong>kept on a SKIPPED or FAILED result</strong>.
 * Discarding them silently threw away exactly the diagnostics needed to understand why a node did
 * not finish.</p>
 */
public class NodeTaskResult {

	private final UUID taskUuid;
	private final String nodeId;
	private final int elementSeq;
	private final NodeState state;
	private final long durationMs;
	private final String message;
	private final String origin;
	private final Map<String, PortPayload> outputs;
	private final Map<String, NodePreview> previews;

	@JsonCreator
	public NodeTaskResult(@JsonProperty("taskUuid") UUID taskUuid, @JsonProperty("nodeId") String nodeId,
		@JsonProperty("elementSeq") Integer elementSeq,
		@JsonProperty("state") NodeState state, @JsonProperty("durationMs") long durationMs,
		@JsonProperty("message") String message, @JsonProperty("outputs") Map<String, PortPayload> outputs,
		@JsonProperty("previews") Map<String, NodePreview> previews, @JsonProperty("origin") String origin) {
		this.taskUuid = taskUuid;
		this.nodeId = Objects.requireNonNull(nodeId, "A node id must be set");
		this.elementSeq = elementSeq == null ? 0 : elementSeq;
		this.state = Objects.requireNonNull(state, "A node state must be set");
		this.durationMs = durationMs;
		this.message = message;
		this.origin = origin;
		this.outputs = outputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
		this.previews = previews == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(previews));
	}

	/**
	 * Backwards-compatible overload for a result with no origin - what a worker built before the
	 * provenance flag travelled on the wire. Read side treats the null as {@code COMPUTED}.
	 */
	public NodeTaskResult(UUID taskUuid, String nodeId, Integer elementSeq, NodeState state, long durationMs,
		String message, Map<String, PortPayload> outputs, Map<String, NodePreview> previews) {
		this(taskUuid, nodeId, elementSeq, state, durationMs, message, outputs, previews, null);
	}

	/**
	 * Backwards-compatible overload for a result with no previews, which is every result of every
	 * run not started in debug mode.
	 */
	public NodeTaskResult(UUID taskUuid, String nodeId, Integer elementSeq, NodeState state, long durationMs,
		String message, Map<String, PortPayload> outputs) {
		this(taskUuid, nodeId, elementSeq, state, durationMs, message, outputs, null, null);
	}

	/** This result with previews attached. Used at the worker's result boundary. */
	public NodeTaskResult withPreviews(Map<String, NodePreview> previews) {
		return new NodeTaskResult(taskUuid, nodeId, elementSeq, state, durationMs, message, outputs, previews, origin);
	}

	/** This result with the provenance flag attached. Used at the worker's result boundary. */
	public NodeTaskResult withOrigin(String origin) {
		return new NodeTaskResult(taskUuid, nodeId, elementSeq, state, durationMs, message, outputs, previews, origin);
	}

	/**
	 * Small renderings of what each output port carried, keyed by output port id.
	 *
	 * <p>
	 * Empty unless the task asked for them ({@link NodeTask#isCapturePreviews()}). An entry may be
	 * present but carry no bytes — see {@link NodePreview#getSkippedReason()}.
	 * </p>
	 *
	 * @return the previews, never null
	 */
	public Map<String, NodePreview> getPreviews() {
		return previews;
	}

	public static NodeTaskResult completed(UUID taskUuid, String nodeId, long durationMs, Map<String, PortPayload> outputs) {
		return new NodeTaskResult(taskUuid, nodeId, 0, NodeState.COMPLETED, durationMs, null, outputs);
	}

	public static NodeTaskResult completed(UUID taskUuid, String nodeId, int elementSeq, long durationMs,
		Map<String, PortPayload> outputs) {
		return new NodeTaskResult(taskUuid, nodeId, elementSeq, NodeState.COMPLETED, durationMs, null, outputs);
	}

	public static NodeTaskResult failed(UUID taskUuid, String nodeId, long durationMs, String message) {
		return new NodeTaskResult(taskUuid, nodeId, 0, NodeState.FAILED, durationMs, message, null);
	}

	/**
	 * A failure that still carries whatever the node managed to emit before it broke.
	 */
	public static NodeTaskResult failed(UUID taskUuid, String nodeId, int elementSeq, long durationMs, String message,
		Map<String, PortPayload> outputs) {
		return new NodeTaskResult(taskUuid, nodeId, elementSeq, NodeState.FAILED, durationMs, message, outputs);
	}

	public static NodeTaskResult skipped(String nodeId, String reason) {
		return new NodeTaskResult(null, nodeId, 0, NodeState.SKIPPED, 0, reason, null);
	}

	public static NodeTaskResult skipped(String nodeId, int elementSeq, String reason) {
		return new NodeTaskResult(null, nodeId, elementSeq, NodeState.SKIPPED, 0, reason, null);
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

	/**
	 * @return which element of a fanned-out sequence this result covers; 0 when the node ran once
	 *         for the whole item
	 */
	public int getElementSeq() {
		return elementSeq;
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

	/**
	 * Where the value came from: {@code COMPUTED}, {@code LOCAL} (served from this worker's cache) or {@code REMOTE} (fetched from Loom).
	 *
	 * <p>
	 * A String rather than an enum on purpose: pipeline-model must not depend on cortex/api, the same reason {@code PortPayload.cardinality} is a
	 * String. Null means the worker predates the flag - readers must treat null as {@code COMPUTED}.
	 * </p>
	 *
	 * @return the origin, or null when the producer did not report one
	 */
	public String getOrigin() {
		return origin;
	}

	/** @return node outputs keyed by output port id, never null */
	public Map<String, PortPayload> getOutputs() {
		return outputs;
	}

	/**
	 * The payload of one output port.
	 *
	 * @return the payload, or null when this node emitted nothing on that port
	 */
	public PortPayload output(String portId) {
		return outputs.get(portId);
	}

	/**
	 * Read this node's filter verdict, if it produced one.
	 *
	 * <p>
	 * A filter declares a single {@code control/filter} output port. Rather than peeking at a
	 * well-known map key, this looks for the first payload of that content type — so a filter is
	 * free to name its port whatever it likes and branch routing still works.
	 * </p>
	 *
	 * @return the verdict, or null when this node is not a filter
	 */
	@JsonIgnore
	public Boolean getFilterPassed() {
		for (PortPayload payload : outputs.values()) {
			if (payload.getContentType() != null && payload.getContentType().startsWith("control/")) {
				Object value = payload.single();
				if (value instanceof Boolean b) {
					return b;
				}
				// Persistent caches on the Cortex side stringify values, so a boolean can
				// arrive as "true"/"false". Tolerate that rather than silently mis-routing.
				if (value instanceof String s) {
					return Boolean.parseBoolean(s);
				}
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return "NodeTaskResult[" + nodeId + (elementSeq > 0 ? " #" + elementSeq : "") + " " + state
			+ (message != null ? ": " + message : "") + "]";
	}
}
