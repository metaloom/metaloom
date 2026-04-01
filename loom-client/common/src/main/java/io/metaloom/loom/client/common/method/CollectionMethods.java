package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.collection.CollectionUpdateRequest;

public interface CollectionMethods {

	LoomClientRequest<CollectionResponse> loadCollection(UUID collectionUuid);

	LoomClientRequest<CollectionResponse> createCollection(CollectionCreateRequest request);

	LoomClientRequest<CollectionResponse> updateCollection(UUID collectionUuid, CollectionUpdateRequest request);

	LoomClientRequest<CollectionListResponse> listCollections();

	LoomClientRequest<NoResponse> deleteCollection(UUID collectionUuid);

}
