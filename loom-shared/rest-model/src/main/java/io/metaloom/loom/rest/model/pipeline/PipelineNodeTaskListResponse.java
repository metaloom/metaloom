package io.metaloom.loom.rest.model.pipeline;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * Paged response for the node executions of a single pipeline run item.
 */
public class PipelineNodeTaskListResponse extends AbstractListResponse<PipelineNodeTaskListResponse, PipelineNodeTaskRecord> implements RestResponseModel<PipelineNodeTaskListResponse> {

	public PipelineNodeTaskListResponse() {
	}

	@Override
	public PipelineNodeTaskListResponse self() {
		return this;
	}

}
