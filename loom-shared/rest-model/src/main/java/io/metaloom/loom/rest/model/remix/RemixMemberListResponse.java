package io.metaloom.loom.rest.model.remix;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class RemixMemberListResponse extends AbstractListResponse<RemixMemberListResponse, RemixMemberResponse> {

	@Override
	public RemixMemberListResponse self() {
		return this;
	}

}
