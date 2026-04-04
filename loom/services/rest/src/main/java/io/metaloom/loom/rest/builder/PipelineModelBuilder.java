package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;

public interface PipelineModelBuilder extends ModelBuilder, UserModelBuilder {

	default PipelineResponse toResponse(Pipeline pipeline) {
		PipelineResponse response = new PipelineResponse();
		response.setUuid(pipeline.getUuid());
		response.setName(pipeline.getName());
		response.setDescription(pipeline.getDescription());
		response.setDefinition(pipeline.getDefinition());
		response.setEnabled(pipeline.isEnabled());
		response.setPriority(pipeline.getPriority());
		response.setDryRun(pipeline.isDryRun());
		setStatus(pipeline, response);
		return response;
	}

	default PipelineListResponse toPipelineList(Page<Pipeline> page) {
		return setPage(new PipelineListResponse(), page, this::toResponse);
	}

}
