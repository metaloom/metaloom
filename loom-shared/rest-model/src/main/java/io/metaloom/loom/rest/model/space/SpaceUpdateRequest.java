package io.metaloom.loom.rest.model.space;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class SpaceUpdateRequest extends AbstractMetaModel<SpaceUpdateRequest> implements RestRequestModel, SpaceModel<SpaceUpdateRequest> {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public SpaceUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public SpaceUpdateRequest self() {
		return this;
	}

}
