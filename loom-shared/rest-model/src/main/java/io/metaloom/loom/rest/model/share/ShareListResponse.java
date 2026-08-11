package io.metaloom.loom.rest.model.share;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ShareListResponse extends AbstractListResponse<ShareListResponse, ShareResponse> {

	@Override
	public ShareListResponse self() {
		return this;
	}

}
