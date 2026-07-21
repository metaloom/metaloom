package io.metaloom.loom.rest.model.pipeline;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * Paged response for the items of a single pipeline run.
 */
public class PipelineRunItemListResponse extends AbstractListResponse<PipelineRunItemListResponse, PipelineRunItemRecord> implements RestResponseModel<PipelineRunItemListResponse> {

	public PipelineRunItemListResponse() {
	}

	@Override
	public PipelineRunItemListResponse self() {
		return this;
	}

}
