package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;

public interface PipelineMethods {

	LoomClientRequest<PipelineResponse> loadPipeline(UUID uuid);

	LoomClientRequest<PipelineResponse> createPipeline(PipelineCreateRequest request);

	LoomClientRequest<PipelineResponse> updatePipeline(UUID uuid, PipelineUpdateRequest request);

	LoomClientRequest<PipelineListResponse> listPipelines();

	LoomClientRequest<PipelineRunListResponse> listPipelineRuns(UUID pipelineUuid);

	LoomClientRequest<PipelineRunRecord> loadPipelineRun(UUID pipelineUuid, UUID runUuid);

	LoomClientRequest<PipelineRunItemListResponse> listPipelineRunItems(UUID pipelineUuid, UUID runUuid);

	LoomClientRequest<NoResponse> deletePipeline(UUID uuid);
}
