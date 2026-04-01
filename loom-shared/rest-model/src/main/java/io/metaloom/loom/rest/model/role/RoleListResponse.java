package io.metaloom.loom.rest.model.role;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class RoleListResponse extends AbstractListResponse<RoleListResponse, RoleResponse> {

	@Override
	public RoleListResponse self() {
		return this;
	}

}
