package io.metaloom.loom.rest.model.collection;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class CollectionUpdateRequest extends AbstractMetaModel<CollectionUpdateRequest> implements RestRequestModel {

	private String name;

	public String getName() {
		return name;
	}

	public CollectionUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public CollectionUpdateRequest self() {
		return this;
	}

}
