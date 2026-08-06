package io.metaloom.loom.rest.model.pipeline;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.vertx.core.json.JsonObject;

/**
 * One node execution within a pipeline run item - the finest granularity the run engine records.
 *
 * <p>
 * A run fans out into items (one per discovered media file) and each item into node tasks (one per graph node, and one <em>per element</em> for a node
 * downstream of a {@code MANY} output - see {@link #getElementSeq()}). This is the record that answers "which node failed, how often was it retried, and what
 * did it actually emit", which nothing else in the API can.
 * </p>
 */
public class PipelineNodeTaskRecord implements RestResponseModel<PipelineNodeTaskRecord> {

	@JsonPropertyDescription("Unique identifier of the node task.")
	private UUID uuid;

	@JsonPropertyDescription("UUID of the run item this task belongs to.")
	private UUID itemUuid;

	@JsonPropertyDescription("UUID of the pipeline run this task belongs to.")
	private UUID runUuid;

	@JsonPropertyDescription("Graph node id this task executed, as written in the pipeline definition.")
	private String nodeId;

	@JsonPropertyDescription("Node kind (type) that was executed, e.g. 'sha512' or 'whisper'.")
	private String nodeKind;

	@JsonPropertyDescription("Which element of a fanned-out sequence this execution handled; 0 when the node runs once per item.")
	private int elementSeq;

	@JsonPropertyDescription("Which attempt at this execution the row records; 0 for the only run of an ordinary task, counting up per operator-requested re-execution with different settings.")
	private int generation;

	@JsonPropertyDescription("Current state: PENDING, RUNNING, COMPLETED, FAILED, SKIPPED or DEAD_LETTER.")
	private NodeTaskState state;

	@JsonPropertyDescription("How many times this execution has been attempted.")
	private int attempt;

	@JsonPropertyDescription("Attempt ceiling before the task is dead-lettered.")
	private int maxAttempts;

	@JsonPropertyDescription("Worker node id currently holding the lease, null when not leased.")
	private String leasedBy;

	@JsonPropertyDescription("When the execution started, null while pending.")
	private Instant started;

	@JsonPropertyDescription("When the execution settled, null while unsettled.")
	private Instant finished;

	@JsonPropertyDescription("Execution duration in milliseconds, null while unsettled.")
	private Long durationMs;

	@JsonPropertyDescription("Error message if the execution failed.")
	private String errorMessage;

	/**
	 * What the node emitted, keyed by <strong>output port id</strong>.
	 *
	 * <p>
	 * Each value is a {@code PortPayload}: {@code {contentType, cardinality, elements:[{origin, value}]}}. Outputs are deliberately retained on
	 * {@code SKIPPED} and {@code FAILED} results too - discarding them would throw away the diagnostics that explain the non-completion, which is exactly
	 * what someone reading this record is looking for.
	 * </p>
	 */
	@JsonPropertyDescription("Outputs keyed by output port id; each value is a PortPayload {contentType, cardinality, elements}.")
	private JsonObject outputs;

	/**
	 * Metadata for the debugging previews attached to this execution, keyed by output port id.
	 *
	 * <p>
	 * Deliberately <strong>metadata only</strong> - {@code mimeType}, {@code width}, {@code height},
	 * {@code url}, or a {@code skippedReason}. The bytes are fetched separately from the {@code url},
	 * so a graph with ten image ports does not put ten base64 blobs into one JSON response that the
	 * browser then cannot cache per image.
	 * </p>
	 *
	 * <p>
	 * Absent unless the run was started with {@code debug}.
	 * </p>
	 */
	@JsonPropertyDescription("Preview metadata keyed by output port id: {mimeType, width, height, url} or {skippedReason}. Bytes are fetched from the url.")
	private JsonObject previews;

	public PipelineNodeTaskRecord() {
	}

	public UUID getUuid() {
		return uuid;
	}

	public PipelineNodeTaskRecord setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public UUID getItemUuid() {
		return itemUuid;
	}

	public PipelineNodeTaskRecord setItemUuid(UUID itemUuid) {
		this.itemUuid = itemUuid;
		return this;
	}

	public UUID getRunUuid() {
		return runUuid;
	}

	public PipelineNodeTaskRecord setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public PipelineNodeTaskRecord setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public PipelineNodeTaskRecord setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public int getElementSeq() {
		return elementSeq;
	}

	public PipelineNodeTaskRecord setElementSeq(int elementSeq) {
		this.elementSeq = elementSeq;
		return this;
	}

	public int getGeneration() {
		return generation;
	}

	public PipelineNodeTaskRecord setGeneration(int generation) {
		this.generation = generation;
		return this;
	}

	public NodeTaskState getState() {
		return state;
	}

	public PipelineNodeTaskRecord setState(NodeTaskState state) {
		this.state = state;
		return this;
	}

	public int getAttempt() {
		return attempt;
	}

	public PipelineNodeTaskRecord setAttempt(int attempt) {
		this.attempt = attempt;
		return this;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public PipelineNodeTaskRecord setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
		return this;
	}

	public String getLeasedBy() {
		return leasedBy;
	}

	public PipelineNodeTaskRecord setLeasedBy(String leasedBy) {
		this.leasedBy = leasedBy;
		return this;
	}

	public Instant getStarted() {
		return started;
	}

	public PipelineNodeTaskRecord setStarted(Instant started) {
		this.started = started;
		return this;
	}

	public Instant getFinished() {
		return finished;
	}

	public PipelineNodeTaskRecord setFinished(Instant finished) {
		this.finished = finished;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public PipelineNodeTaskRecord setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public PipelineNodeTaskRecord setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	public JsonObject getOutputs() {
		return outputs;
	}

	public PipelineNodeTaskRecord setOutputs(JsonObject outputs) {
		this.outputs = outputs;
		return this;
	}

	public JsonObject getPreviews() {
		return previews;
	}

	public PipelineNodeTaskRecord setPreviews(JsonObject previews) {
		this.previews = previews;
		return this;
	}

	@Override
	public PipelineNodeTaskRecord self() {
		return this;
	}

}
