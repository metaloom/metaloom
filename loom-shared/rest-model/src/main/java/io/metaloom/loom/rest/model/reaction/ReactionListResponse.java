package io.metaloom.loom.rest.model.reaction;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ReactionListResponse extends AbstractListResponse<ReactionListResponse, ReactionResponse> {

	@Override
	public ReactionListResponse self() {
		return this;
	}

}
