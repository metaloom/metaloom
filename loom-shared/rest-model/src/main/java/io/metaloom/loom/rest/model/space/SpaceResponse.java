package io.metaloom.loom.rest.model.space;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class SpaceResponse extends AbstractCreatorEditorRestResponse<SpaceResponse> implements SpaceModel<SpaceResponse> {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public SpaceResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public SpaceResponse self() {
		return this;
	}

}
