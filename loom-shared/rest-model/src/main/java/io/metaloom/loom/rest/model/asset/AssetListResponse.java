package io.metaloom.loom.rest.model.asset;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class AssetListResponse extends AbstractListResponse<AssetListResponse, AssetResponse> {

	@Override
	public AssetListResponse self() {
		return this;
	}

}
