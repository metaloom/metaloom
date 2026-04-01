package io.metaloom.loom.rest.model.group;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface GroupExamples extends ExampleValues {

	default Example groupResponseExample() {
		return new ExampleImpl(groupResponse(), "The group response", HttpResponseStatus.OK);
	}

	default Example groupUpdateRequestExample() {
		return new ExampleImpl(groupUpdateRequest(), "The group update request", HttpResponseStatus.OK);
	}

	default Example groupCreateRequestExample() {
		return new ExampleImpl(groupCreateRequest(), "The group create request", HttpResponseStatus.CREATED);
	}

	default Example groupListResponseExample() {
		return new ExampleImpl(groupListResponse(), "The group list response", HttpResponseStatus.OK);
	}

	default GroupResponse groupResponse() {
		GroupResponse model = new GroupResponse();
		model.setUuid(uuidC());
		model.setName("Guests");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default GroupReference groupReference() {
		GroupReference model = new GroupReference();
		model.setUuid(uuidA());
		model.setName("Guests");
		return model;
	}

	default GroupUpdateRequest groupUpdateRequest() {
		GroupUpdateRequest model = new GroupUpdateRequest();
		model.setName("NewGuests");
		model.setMeta(meta());
		return model;
	}

	default GroupCreateRequest groupCreateRequest() {
		GroupCreateRequest model = new GroupCreateRequest();
		model.setName("Guests");
		model.setMeta(meta());
		return model;
	}

	default GroupListResponse groupListResponse() {
		GroupListResponse model = new GroupListResponse();
		model.setMetainfo(pagingInfo());
		model.add(groupResponse());
		model.add(groupResponse());
		return model;
	}
}
