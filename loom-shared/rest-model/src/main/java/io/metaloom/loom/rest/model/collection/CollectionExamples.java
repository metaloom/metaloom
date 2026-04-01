package io.metaloom.loom.rest.model.collection;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface CollectionExamples extends ExampleValues {

	default Example collectionUpdateRequestExample() {
		return new ExampleImpl(collectionUpdateRequest(), "The collection update request", HttpResponseStatus.OK);
	}

	default Example collectionCreateRequestExample() {
		return new ExampleImpl(collectionCreateRequest(), "The collection create request", HttpResponseStatus.CREATED);
	}

	default Example collectionResponseExample() {
		return new ExampleImpl(collectionResponse(), "The collection response", HttpResponseStatus.OK);
	}

	default Example collectionListResponseExample() {
		return new ExampleImpl(collectionListResponse(), "The collection list response", HttpResponseStatus.OK);
	}

	default CollectionResponse collectionResponse() {
		CollectionResponse model = new CollectionResponse();
		model.setUuid(uuidC());
		model.setName("The collection name");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default CollectionListResponse collectionListResponse() {
		CollectionListResponse model = new CollectionListResponse();
		model.setMetainfo(pagingInfo());
		model.add(collectionResponse());
		model.add(collectionResponse());
		return model;
	}

	default CollectionUpdateRequest collectionUpdateRequest() {
		CollectionUpdateRequest model = new CollectionUpdateRequest();
		model.setName("The collection name");
		model.setMeta(meta());
		return model;
	}

	default CollectionCreateRequest collectionCreateRequest() {
		CollectionCreateRequest model = new CollectionCreateRequest();
		model.setName("The collection name");
		model.setMeta(meta());
		return model;
	}

}
