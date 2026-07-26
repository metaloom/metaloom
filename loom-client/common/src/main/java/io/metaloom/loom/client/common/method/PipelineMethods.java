package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunStatsResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionRestoreRequest;

public interface PipelineMethods {

	LoomClientRequest<PipelineResponse> loadPipeline(UUID uuid);

	LoomClientRequest<PipelineResponse> createPipeline(PipelineCreateRequest request);

	LoomClientRequest<PipelineResponse> updatePipeline(UUID uuid, PipelineUpdateRequest request);

	LoomClientRequest<PipelineListResponse> listPipelines();

	LoomClientRequest<PipelineRunListResponse> listPipelineRuns(UUID pipelineUuid);

	LoomClientRequest<PipelineRunStatsResponse> loadPipelineRunStats();

	LoomClientRequest<PipelineRunRecord> loadPipelineRun(UUID pipelineUuid, UUID runUuid);

	LoomClientRequest<PipelineRunItemListResponse> listPipelineRunItems(UUID pipelineUuid, UUID runUuid);

	LoomClientRequest<NoResponse> deletePipeline(UUID uuid);

	// RUN LIFECYCLE

	/**
	 * Trigger a run of the pipeline. The server replies 202 once a processor has accepted
	 * the run, or 503 when no registered processor can serve it.
	 */
	LoomClientRequest<PipelineRunResponse> runPipeline(UUID pipelineUuid, PipelineRunRequest request);

	/**
	 * Suspend an in-flight run. Dispatch of new node tasks stops and the source scan is
	 * throttled; work already handed to a worker settles normally.
	 */
	LoomClientRequest<GenericMessageResponse> pausePipelineRun(UUID pipelineUuid, UUID runUuid);

	/** Resume a previously paused run. Fails with 409 if the run is not live any more. */
	LoomClientRequest<GenericMessageResponse> resumePipelineRun(UUID pipelineUuid, UUID runUuid);

	/**
	 * Cancel an in-flight run. In-flight worker tasks cannot be recalled, so they settle
	 * and their late results are ignored.
	 */
	LoomClientRequest<GenericMessageResponse> cancelPipelineRun(UUID pipelineUuid, UUID runUuid);

	// VERSIONS

	LoomClientRequest<PipelineVersionListResponse> listPipelineVersions(UUID pipelineUuid);

	LoomClientRequest<PipelineResponse> loadPipelineVersion(UUID pipelineUuid, int version);

	/** Restore an older version. This copies it forward as a new version rather than rewinding. */
	LoomClientRequest<PipelineResponse> restorePipelineVersion(UUID pipelineUuid, int version,
		PipelineVersionRestoreRequest request);
}
