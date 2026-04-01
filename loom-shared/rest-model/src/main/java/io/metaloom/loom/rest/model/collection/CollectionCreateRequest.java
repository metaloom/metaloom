package io.metaloom.loom.rest.model.collection;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class CollectionCreateRequest extends AbstractMetaModel<CollectionCreateRequest> implements RestRequestModel {
	
	private String name;

	public String getName() {
		return name;
	}

	public CollectionCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public CollectionCreateRequest self() {
		return this;
	}

}
