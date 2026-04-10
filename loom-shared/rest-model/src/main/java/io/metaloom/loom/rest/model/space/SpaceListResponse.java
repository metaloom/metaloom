package io.metaloom.loom.rest.model.space;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class SpaceListResponse extends AbstractListResponse<SpaceListResponse, SpaceResponse> {

	@Override
	public SpaceListResponse self() {
		return this;
	}

}
