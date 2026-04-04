package io.metaloom.loom.rest.model.pipeline;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class PipelineListResponse extends AbstractListResponse<PipelineListResponse, PipelineResponse> {

	@Override
	public PipelineListResponse self() {
		return this;
	}

}
