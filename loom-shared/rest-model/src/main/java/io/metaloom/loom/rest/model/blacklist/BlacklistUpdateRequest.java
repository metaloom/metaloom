package io.metaloom.loom.rest.model.blacklist;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class BlacklistUpdateRequest extends AbstractMetaModel<BlacklistUpdateRequest> implements RestRequestModel, BlacklistModel<BlacklistUpdateRequest> {

	private String name;

	private String assetUuid;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public BlacklistUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getAssetUuid() {
		return assetUuid;
	}

	@Override
	public BlacklistUpdateRequest setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public BlacklistUpdateRequest self() {
		return this;
	}
}
