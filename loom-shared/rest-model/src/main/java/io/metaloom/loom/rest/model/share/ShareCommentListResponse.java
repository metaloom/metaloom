package io.metaloom.loom.rest.model.share;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ShareCommentListResponse extends AbstractListResponse<ShareCommentListResponse, ShareCommentResponse> {

	@Override
	public ShareCommentListResponse self() {
		return this;
	}

}
