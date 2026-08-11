package io.metaloom.loom.rest.model.share;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ShareReactionListResponse extends AbstractListResponse<ShareReactionListResponse, ShareReactionResponse> {

	@Override
	public ShareReactionListResponse self() {
		return this;
	}

}
