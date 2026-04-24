package io.metaloom.loom.rest.model.blacklist;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class BlacklistCreateRequest extends AbstractMetaModel<BlacklistCreateRequest> implements RestRequestModel, BlacklistModel<BlacklistCreateRequest> {

	private String name;

	private String assetUuid;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public BlacklistCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getAssetUuid() {
		return assetUuid;
	}

	@Override
	public BlacklistCreateRequest setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public BlacklistCreateRequest self() {
		return this;
	}
}
