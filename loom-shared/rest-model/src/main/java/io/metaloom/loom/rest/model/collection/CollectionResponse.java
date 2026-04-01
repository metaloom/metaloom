package io.metaloom.loom.rest.model.collection;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class CollectionResponse extends AbstractCreatorEditorRestResponse<CollectionResponse> implements CollectionModel<CollectionResponse> {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public CollectionResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public CollectionResponse self() {
		return this;
	}

}
