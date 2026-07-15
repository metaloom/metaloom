package io.metaloom.loom.rest.model.pipeline;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.common.AbstractListResponse;
import io.metaloom.loom.rest.model.common.PagingInfo;

/**
 * Paged response for pipeline run history.
 */
public class PipelineRunListResponse extends AbstractListResponse<PipelineRunListResponse, PipelineRunRecord> implements RestResponseModel<PipelineRunListResponse> {

	public PipelineRunListResponse() {
	}

	@Override
	public PipelineRunListResponse self() {
		return this;
	}

}