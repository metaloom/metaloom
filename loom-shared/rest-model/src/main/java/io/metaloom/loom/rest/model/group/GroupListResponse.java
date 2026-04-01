package io.metaloom.loom.rest.model.group;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class GroupListResponse extends AbstractListResponse<GroupListResponse, GroupResponse> {

	@Override
	public GroupListResponse self() {
		return this;
	}

}
