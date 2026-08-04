package io.metaloom.loom.rest.service;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.vertx.core.json.JsonObject;

/**
 * A plain in-memory {@link PipelineNodeTask}, so reaper tests need no database.
 */
public class FakePipelineNodeTask implements PipelineNodeTask {

	private UUID itemUuid;
	private UUID runUuid;
	private String nodeId;
	private String nodeKind;
	/** Which element of a fanned-out sequence this task covers; 0 when the node runs once per item. */
	private int elementSeq;
	private String state = "PENDING";
	private int attempt;
	private int maxAttempts = 1;
	private String leasedBy;
	private Instant leaseExpiresAt;
	private Instant started;
	private Instant finished;
	private Long durationMs;
	private String errorMessage;
	private JsonObject outputs;
	private JsonObject previews;
	private JsonObject meta;
	private UUID uuid;
	private UUID creatorUuid;
	private UUID editorUuid;
	private Instant created;
	private Instant edited;

	@Override
	public UUID getItemUuid() {
		return itemUuid;
	}

	@Override
	public PipelineNodeTask setItemUuid(UUID itemUuid) {
		this.itemUuid = itemUuid;
		return this;
	}

	@Override
	public UUID getRunUuid() {
		return runUuid;
	}

	@Override
	public PipelineNodeTask setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public PipelineNodeTask setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	@Override
	public int getElementSeq() {
		return elementSeq;
	}

	@Override
	public PipelineNodeTask setElementSeq(int elementSeq) {
		this.elementSeq = elementSeq;
		return this;
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public PipelineNodeTask setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getState() {
		return state;
	}

	@Override
	public PipelineNodeTask setState(String state) {
		this.state = state;
		return this;
	}

	@Override
	public int getAttempt() {
		return attempt;
	}

	@Override
	public PipelineNodeTask setAttempt(int attempt) {
		this.attempt = attempt;
		return this;
	}

	@Override
	public int getMaxAttempts() {
		return maxAttempts;
	}

	@Override
	public PipelineNodeTask setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
		return this;
	}

	@Override
	public String getLeasedBy() {
		return leasedBy;
	}

	@Override
	public PipelineNodeTask setLeasedBy(String leasedBy) {
		this.leasedBy = leasedBy;
		return this;
	}

	@Override
	public Instant getLeaseExpiresAt() {
		return leaseExpiresAt;
	}

	@Override
	public PipelineNodeTask setLeaseExpiresAt(Instant leaseExpiresAt) {
		this.leaseExpiresAt = leaseExpiresAt;
		return this;
	}

	@Override
	public Instant getStarted() {
		return started;
	}

	@Override
	public PipelineNodeTask setStarted(Instant started) {
		this.started = started;
		return this;
	}

	@Override
	public Instant getFinished() {
		return finished;
	}

	@Override
	public PipelineNodeTask setFinished(Instant finished) {
		this.finished = finished;
		return this;
	}

	@Override
	public Long getDurationMs() {
		return durationMs;
	}

	@Override
	public PipelineNodeTask setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	@Override
	public String getErrorMessage() {
		return errorMessage;
	}

	@Override
	public PipelineNodeTask setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	@Override
	public JsonObject getOutputs() {
		return outputs;
	}

	@Override
	public PipelineNodeTask setPreviews(JsonObject previews) {
		this.previews = previews;
		return this;
	}

	@Override
	public JsonObject getPreviews() {
		return previews;
	}

	@Override
	public PipelineNodeTask setOutputs(JsonObject outputs) {
		this.outputs = outputs;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public PipelineNodeTask setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public PipelineNodeTask setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	@Override
	public UUID getCreatorUuid() {
		return creatorUuid;
	}

	@Override
	public PipelineNodeTask setCreatorUuid(UUID creatorUuid) {
		this.creatorUuid = creatorUuid;
		return this;
	}

	@Override
	public UUID getEditorUuid() {
		return editorUuid;
	}

	@Override
	public PipelineNodeTask setEditorUuid(UUID editorUuid) {
		this.editorUuid = editorUuid;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public PipelineNodeTask setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public Instant getEdited() {
		return edited;
	}

	@Override
	public PipelineNodeTask setEdited(Instant edited) {
		this.edited = edited;
		return this;
	}

}
