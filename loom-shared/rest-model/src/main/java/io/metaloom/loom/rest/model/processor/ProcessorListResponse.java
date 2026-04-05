package io.metaloom.loom.rest.model.processor;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ProcessorListResponse extends AbstractListResponse<ProcessorListResponse, ProcessorResponse> {

	@Override
	public ProcessorListResponse self() {
		return this;
	}
}
