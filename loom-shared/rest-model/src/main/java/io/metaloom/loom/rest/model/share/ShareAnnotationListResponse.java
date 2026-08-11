package io.metaloom.loom.rest.model.share;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ShareAnnotationListResponse extends AbstractListResponse<ShareAnnotationListResponse, ShareAnnotationResponse> {

	@Override
	public ShareAnnotationListResponse self() {
		return this;
	}

}
