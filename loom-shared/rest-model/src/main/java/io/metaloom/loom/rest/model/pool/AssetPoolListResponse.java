package io.metaloom.loom.rest.model.pool;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class AssetPoolListResponse extends AbstractListResponse<AssetPoolListResponse, AssetPoolResponse> {

	@Override
	public AssetPoolListResponse self() {
		return this;
	}

}
