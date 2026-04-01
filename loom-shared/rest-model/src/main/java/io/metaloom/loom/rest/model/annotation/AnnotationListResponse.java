package io.metaloom.loom.rest.model.annotation;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class AnnotationListResponse extends AbstractListResponse<AnnotationListResponse, AnnotationResponse> {

	@Override
	public AnnotationListResponse self() {
		return this;
	}

}
