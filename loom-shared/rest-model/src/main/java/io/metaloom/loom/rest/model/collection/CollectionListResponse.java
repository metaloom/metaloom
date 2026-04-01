package io.metaloom.loom.rest.model.collection;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class CollectionListResponse extends AbstractListResponse<CollectionListResponse, CollectionResponse> {

	@Override
	public CollectionListResponse self() {
		return this;
	}

}
