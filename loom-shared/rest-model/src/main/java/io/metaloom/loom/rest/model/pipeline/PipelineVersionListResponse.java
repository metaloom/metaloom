package io.metaloom.loom.rest.model.pipeline;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * Paged list of a pipeline's versions. Each entry is a {@link PipelineResponse} rendered from one historic version — the {@code uuid} identifies the pipeline,
 * {@code versionUuid} / {@code versionNumber} identify the version.
 */
public class PipelineVersionListResponse extends AbstractListResponse<PipelineVersionListResponse, PipelineResponse> {

	public PipelineVersionListResponse() {
	}

	@Override
	public PipelineVersionListResponse self() {
		return this;
	}
}
