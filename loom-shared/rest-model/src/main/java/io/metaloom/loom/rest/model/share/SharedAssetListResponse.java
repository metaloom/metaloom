package io.metaloom.loom.rest.model.share;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class SharedAssetListResponse extends AbstractListResponse<SharedAssetListResponse, SharedAssetResponse> {

	@Override
	public SharedAssetListResponse self() {
		return this;
	}

}
