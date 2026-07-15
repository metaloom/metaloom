package io.metaloom.loom.rest.model.pipeline;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * A single pipeline run record returned by the run history endpoint.
 */
public class PipelineRunRecord implements RestResponseModel<PipelineRunRecord> {

	@JsonPropertyDescription("Unique identifier of the pipeline run.")
	private UUID uuid;

	@JsonPropertyDescription("UUID of the pipeline definition that was executed.")
	private UUID pipelineUuid;

	@JsonPropertyDescription("Version of the pipeline definition at the time of execution.")
	private int pipelineVersion;

	@JsonPropertyDescription("When the pipeline run started (ISO 8601).")
	private String started;

	@JsonPropertyDescription("When the pipeline run finished (ISO 8601), null if still running.")
	private String finished;

	@JsonPropertyDescription("Current status: PENDING, RUNNING, SUCCESS, FAILED, PARTIAL, CANCELLED.")
	private String status;

	@JsonPropertyDescription("Total number of media items processed.")
	private int mediaCount;

	@JsonPropertyDescription("Number of media items processed successfully.")
	private int successCount;

	@JsonPropertyDescription("Number of media items that failed.")
	private int failureCount;

	@JsonPropertyDescription("Number of media items skipped.")
	private int skippedCount;

	@JsonPropertyDescription("Whether this was a dry run (no side effects).")
	private boolean dryRun;

	@JsonPropertyDescription("Error message if the run failed.")
	private String errorMessage;

	@JsonPropertyDescription("Total duration of the run in milliseconds.")
	private Long durationMs;

	@JsonPropertyDescription("Additional metadata (node stats, etc.).")
	private io.vertx.core.json.JsonObject meta;

	public PipelineRunRecord() {
	}

	public UUID getUuid() {
		return uuid;
	}

	public PipelineRunRecord setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public UUID getPipelineUuid() {
		return pipelineUuid;
	}

	public PipelineRunRecord setPipelineUuid(UUID pipelineUuid) {
		this.pipelineUuid = pipelineUuid;
		return this;
	}

	public int getPipelineVersion() {
		return pipelineVersion;
	}

	public PipelineRunRecord setPipelineVersion(int pipelineVersion) {
		this.pipelineVersion = pipelineVersion;
		return this;
	}

	public String getStarted() {
		return started;
	}

	public PipelineRunRecord setStarted(String started) {
		this.started = started;
		return this;
	}

	public String getFinished() {
		return finished;
	}

	public PipelineRunRecord setFinished(String finished) {
		this.finished = finished;
		return this;
	}

	public String getStatus() {
		return status;
	}

	public PipelineRunRecord setStatus(String status) {
		this.status = status;
		return this;
	}

	public int getMediaCount() {
		return mediaCount;
	}

	public PipelineRunRecord setMediaCount(int mediaCount) {
		this.mediaCount = mediaCount;
		return this;
	}

	public int getSuccessCount() {
		return successCount;
	}

	public PipelineRunRecord setSuccessCount(int successCount) {
		this.successCount = successCount;
		return this;
	}

	public int getFailureCount() {
		return failureCount;
	}

	public PipelineRunRecord setFailureCount(int failureCount) {
		this.failureCount = failureCount;
		return this;
	}

	public int getSkippedCount() {
		return skippedCount;
	}

	public PipelineRunRecord setSkippedCount(int skippedCount) {
		this.skippedCount = skippedCount;
		return this;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public PipelineRunRecord setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public PipelineRunRecord setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public PipelineRunRecord setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public io.vertx.core.json.JsonObject getMeta() {
		return meta;
	}

	public PipelineRunRecord setMeta(io.vertx.core.json.JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public PipelineRunRecord self() {
		return this;
	}

}