package io.metaloom.loom.db.jooq.dao.pipeline;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.vertx.core.json.JsonObject;

public class PipelineRunImpl extends AbstractEditableElement<PipelineRun> implements PipelineRun {

	private UUID pipelineUuid;
	private int pipelineVersion = 1;
	private Instant started;
	private Instant finished;
	private PipelineRunStatus status = PipelineRunStatus.PENDING;
	private int mediaCount = 0;
	private int successCount = 0;
	private int failureCount = 0;
	private int skippedCount = 0;
	private boolean dryRun = false;
	private String errorMessage;
	private Long durationMs;
	private JsonObject meta;

	@Override
	public UUID getPipelineUuid() {
		return pipelineUuid;
	}

	@Override
	public PipelineRun setPipelineUuid(UUID pipelineUuid) {
		this.pipelineUuid = pipelineUuid;
		return this;
	}

	@Override
	public int getPipelineVersion() {
		return pipelineVersion;
	}

	@Override
	public PipelineRun setPipelineVersion(int pipelineVersion) {
		this.pipelineVersion = pipelineVersion;
		return this;
	}

	@Override
	public Instant getStarted() {
		return started;
	}

	@Override
	public PipelineRun setStarted(Instant started) {
		this.started = started;
		return this;
	}

	@Override
	public Instant getFinished() {
		return finished;
	}

	@Override
	public PipelineRun setFinished(Instant finished) {
		this.finished = finished;
		return this;
	}

	@Override
	public PipelineRunStatus getStatus() {
		return status;
	}

	@Override
	public PipelineRun setStatus(PipelineRunStatus status) {
		this.status = status;
		return this;
	}

	@Override
	public int getMediaCount() {
		return mediaCount;
	}

	@Override
	public PipelineRun setMediaCount(int mediaCount) {
		this.mediaCount = mediaCount;
		return this;
	}

	@Override
	public int getSuccessCount() {
		return successCount;
	}

	@Override
	public PipelineRun setSuccessCount(int successCount) {
		this.successCount = successCount;
		return this;
	}

	@Override
	public int getFailureCount() {
		return failureCount;
	}

	@Override
	public PipelineRun setFailureCount(int failureCount) {
		this.failureCount = failureCount;
		return this;
	}

	@Override
	public int getSkippedCount() {
		return skippedCount;
	}

	@Override
	public PipelineRun setSkippedCount(int skippedCount) {
		this.skippedCount = skippedCount;
		return this;
	}

	@Override
	public boolean isDryRun() {
		return dryRun;
	}

	@Override
	public PipelineRun setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	@Override
	public String getErrorMessage() {
		return errorMessage;
	}

	@Override
	public PipelineRun setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	@Override
	public Long getDurationMs() {
		return durationMs;
	}

	@Override
	public PipelineRun setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public PipelineRun setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}