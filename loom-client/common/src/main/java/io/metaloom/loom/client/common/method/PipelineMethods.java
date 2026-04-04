package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;

public interface PipelineMethods {

	LoomClientRequest<PipelineResponse> loadPipeline(UUID uuid);

	LoomClientRequest<PipelineResponse> createPipeline(PipelineCreateRequest request);

	LoomClientRequest<PipelineResponse> updatePipeline(UUID uuid, PipelineUpdateRequest request);

	LoomClientRequest<PipelineListResponse> listPipelines();

	LoomClientRequest<NoResponse> deletePipeline(UUID uuid);
}
