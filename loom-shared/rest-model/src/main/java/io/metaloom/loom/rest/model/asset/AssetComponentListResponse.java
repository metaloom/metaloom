package io.metaloom.loom.rest.model.asset;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class AssetComponentListResponse extends AbstractListResponse<AssetComponentListResponse, AssetComponentResponse> {

	@Override
	public AssetComponentListResponse self() {
		return this;
	}
}
