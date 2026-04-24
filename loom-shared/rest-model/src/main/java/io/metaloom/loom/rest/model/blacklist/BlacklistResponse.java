package io.metaloom.loom.rest.model.blacklist;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class BlacklistResponse extends AbstractCreatorEditorRestResponse<BlacklistResponse> implements BlacklistModel<BlacklistResponse> {

	private String name;

	private String assetUuid;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public BlacklistResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getAssetUuid() {
		return assetUuid;
	}

	@Override
	public BlacklistResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public BlacklistResponse self() {
		return this;
	}

}
