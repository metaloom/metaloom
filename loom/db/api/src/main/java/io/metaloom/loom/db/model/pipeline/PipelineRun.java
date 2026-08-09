package io.metaloom.loom.db.model.pipeline;

import java.util.UUID;

import io.metaloom.loom.api.pipeline.PipelineRunKind;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

public interface PipelineRun extends CUDElement<PipelineRun> {

	/**
	 * The {@link #getMeta()} key an {@link PipelineRunKind#ADHOC} run stores its executable graph
	 * under. It is the same definition JSON the pipeline catalog stores, so recovery and validation
	 * need no second format.
	 */
	String META_DEFINITION = "definition";

	/**
	 * The pipeline this run executes, or {@code null} for a {@link PipelineRunKind#ADHOC} run, which
	 * carries its definition in {@link #getMeta()} under {@code definition} instead.
	 */
	UUID getPipelineUuid();

	PipelineRun setPipelineUuid(UUID pipelineUuid);

	/** Where the definition came from. Never {@code null}; existing rows read back as {@code PIPELINE}. */
	PipelineRunKind getKind();

	PipelineRun setKind(PipelineRunKind kind);

	int getPipelineVersion();

	PipelineRun setPipelineVersion(int pipelineVersion);

	java.time.Instant getStarted();

	PipelineRun setStarted(java.time.Instant started);

	java.time.Instant getFinished();

	PipelineRun setFinished(java.time.Instant finished);

	PipelineRunStatus getStatus();

	PipelineRun setStatus(PipelineRunStatus status);

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