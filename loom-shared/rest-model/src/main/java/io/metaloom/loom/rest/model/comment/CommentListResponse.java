package io.metaloom.loom.rest.model.comment;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class CommentListResponse extends AbstractListResponse<CommentListResponse, CommentResponse> {

	@Override
	public CommentListResponse self() {
		return this;
	}

}
