package io.metaloom.loom.rest.model.person;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface PersonExamples extends ExampleValues {

	default Example personUpdateRequestExample() {
		return new ExampleImpl(personUpdateRequest(), "The person update request", HttpResponseStatus.OK);
	}

	default Example personCreateRequestExample() {
		return new ExampleImpl(personCreateRequest(), "The person create request", HttpResponseStatus.CREATED);
	}

	default Example personResponseExample() {
		return new ExampleImpl(personResponse(), "The person response", HttpResponseStatus.OK);
	}

	default Example personListResponseExample() {
		return new ExampleImpl(personListResponse(), "The person list response", HttpResponseStatus.OK);
	}

	default PersonResponse personResponse() {
		PersonResponse model = new PersonResponse();
		model.setUuid(uuidC());
		model.setAlias("jdoe");
		model.setFirstname("John");
		model.setLastname("Doe");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default PersonUpdateRequest personUpdateRequest() {
		PersonUpdateRequest model = new PersonUpdateRequest();
		model.setAlias("jdoe_updated");
		model.setFirstname("Jane");
		model.setLastname("Doe");
		model.setMeta(meta());
		return model;
	}

	default PersonCreateRequest personCreateRequest() {
		PersonCreateRequest model = new PersonCreateRequest();
		model.setAlias("jdoe");
		model.setFirstname("John");
		model.setLastname("Doe");
		model.setMeta(meta());
		return model;
	}

	default PersonListResponse personListResponse() {
		PersonListResponse model = new PersonListResponse();
		model.setMetainfo(pagingInfo());
		model.add(personResponse());
		model.add(personResponse());
		return model;
	}

}
