package io.metaloom.loom.rest.model.asset.binary;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class AssetBinaryListResponse extends AbstractListResponse<AssetBinaryListResponse, AssetBinaryResponse> {

	@Override
	public AssetBinaryListResponse self() {
		return this;
	}

}
