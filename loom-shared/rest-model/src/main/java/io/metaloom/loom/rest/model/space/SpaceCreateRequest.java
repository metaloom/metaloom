package io.metaloom.loom.rest.model.space;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class SpaceCreateRequest extends AbstractMetaModel<SpaceCreateRequest> implements RestRequestModel, SpaceModel<SpaceCreateRequest> {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public SpaceCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public SpaceCreateRequest self() {
		return this;
	}

}
