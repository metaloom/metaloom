package io.metaloom.loom.rest.model.blacklist;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class BlacklistListResponse extends AbstractListResponse<BlacklistListResponse, BlacklistResponse> {

	@Override
	public BlacklistListResponse self() {
		return this;
	}

}
