package io.metaloom.loom.db.model.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

public interface PipelineRun extends CUDElement<PipelineRun> {

	UUID getPipelineUuid();

	PipelineRun setPipelineUuid(UUID pipelineUuid);

	int getPipelineVersion();

	PipelineRun setPipelineVersion(int pipelineVersion);

	java.time.Instant getStarted();

	PipelineRun setStarted(java.time.Instant started);

	java.time.Instant getFinished();

	PipelineRun setFinished(java.time.Instant finished);

	String getStatus();

	PipelineRun setStatus(String status);

	int getMediaCount();

	PipelineRun setMediaCount(int mediaCount);

	int getSuccessCount();

	PipelineRun setSuccessCount(int successCount);

	int getFailureCount();

	PipelineRun setFailureCount(int failureCount);

	int getSkippedCount();

	PipelineRun setSkippedCount(int skippedCount);

	boolean isDryRun();

	PipelineRun setDryRun(boolean dryRun);

	String getErrorMessage();

	PipelineRun setErrorMessage(String errorMessage);

	Long getDurationMs();

	PipelineRun setDurationMs(Long durationMs);

	JsonObject getMeta();

	PipelineRun setMeta(JsonObject meta);

}