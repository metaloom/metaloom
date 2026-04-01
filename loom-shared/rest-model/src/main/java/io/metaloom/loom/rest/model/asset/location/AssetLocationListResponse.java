package io.metaloom.loom.rest.model.asset.location;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class AssetLocationListResponse extends AbstractListResponse<AssetLocationListResponse, AssetLocationResponse> {

	@Override
	public AssetLocationListResponse self() {
		return this;
	}

}
