package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;

public interface CollectionModelBuilder extends ModelBuilder, UserModelBuilder {

	default CollectionResponse toResponse(Collection collection) {
		CollectionResponse response = new CollectionResponse();
		response.setName(collection.getName());
		response.setUuid(collection.getUuid());
		setStatus(collection, response);
		return response;
	}

	default CollectionListResponse toCollectionList(Page<Collection> page) {
		return setPage(new CollectionListResponse(), page, this::toResponse);
	}

}
