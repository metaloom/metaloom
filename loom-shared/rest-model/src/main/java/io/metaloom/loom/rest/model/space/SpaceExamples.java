package io.metaloom.loom.rest.model.space;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface SpaceExamples extends ExampleValues {

	default Example spaceUpdateRequestExample() {
		return new ExampleImpl(spaceUpdateRequest(), "The space update request", HttpResponseStatus.OK);
	}

	default Example spaceCreateRequestExample() {
		return new ExampleImpl(spaceCreateRequest(), "The space create request", HttpResponseStatus.CREATED);
	}

	default Example spaceResponseExample() {
		return new ExampleImpl(spaceResponse(), "The space response", HttpResponseStatus.OK);
	}

	default Example spaceListResponseExample() {
		return new ExampleImpl(spaceListResponse(), "The space list response", HttpResponseStatus.OK);
	}

	default SpaceUpdateRequest spaceUpdateRequest() {
		SpaceUpdateRequest request = new SpaceUpdateRequest();
		request.setName("The new name");
		request.setMeta(meta());
		return request;
	}

	default SpaceCreateRequest spaceCreateRequest() {
		SpaceCreateRequest request = new SpaceCreateRequest();
		request.setMeta(meta());
		request.setName("The Space Name");
		return request;
	}

	default SpaceResponse spaceResponse() {
		SpaceResponse response = new SpaceResponse();
		response.setUuid(uuidB());
		response.setMeta(meta());
		response.setName("The Space Name");
		setCreatorEditor(response);
		return response;
	}

	default SpaceListResponse spaceListResponse() {
		SpaceListResponse model = new SpaceListResponse();
		model.setMetainfo(pagingInfo());
		model.add(spaceResponse());
		model.add(spaceResponse());
		return model;
	}

}
