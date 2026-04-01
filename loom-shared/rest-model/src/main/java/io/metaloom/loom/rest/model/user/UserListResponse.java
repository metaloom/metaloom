package io.metaloom.loom.rest.model.user;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class UserListResponse extends AbstractListResponse<UserListResponse, UserResponse> {

	@Override
	public UserListResponse self() {
		return this;
	}

}
