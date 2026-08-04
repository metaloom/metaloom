package io.metaloom.loom.db.jooq.dao.pipeline;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.vertx.core.json.JsonObject;

public class PipelineNodeTaskImpl extends AbstractEditableElement<PipelineNodeTask> implements PipelineNodeTask {

	private UUID itemUuid;
	private UUID runUuid;
	private String nodeId;
	private String nodeKind;
	private String state = "PENDING";
	private int elementSeq = 0;
	private int attempt = 0;
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
	public int getElementSeq() {
		return elementSeq;
	}

	@Override
	public PipelineNodeTask setElementSeq(int elementSeq) {
		this.elementSeq = elementSeq;
		return this;
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
	public PipelineNodeTask setOutputs(JsonObject outputs) {
		this.outputs = outputs;
		return this;
	}

	@Override
	public JsonObject getPreviews() {
		return previews;
	}

	@Override
	public PipelineNodeTask setPreviews(JsonObject previews) {
		this.previews = previews;
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

}
