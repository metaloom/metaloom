package io.metaloom.loom.rest.model.user;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface UserExamples extends ExampleValues {

	default Example userResponseExample() {
		return new ExampleImpl(userResponse(), "The user response", HttpResponseStatus.OK);
	}

	default Example userUpdateRequestExample() {
		return new ExampleImpl(userUpdateRequest(), "The user update request", HttpResponseStatus.OK);
	}

	default Example userCreateRequestExample() {
		return new ExampleImpl(userCreateRequest(), "The user create request", HttpResponseStatus.CREATED);
	}

	default Example userListResponseExample() {
		return new ExampleImpl(userListResponse(), "The user list response", HttpResponseStatus.OK);
	}

	default UserResponse userResponse() {
		UserResponse model = new UserResponse();
		model.setUuid(uuidA());
		model.setUsername("joedoe");
		model.setEmail("joedoe@metaloom.io");
		model.setFirstname("Joe");
		model.setLastname("Doe");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default UserUpdateRequest userUpdateRequest() {
		UserUpdateRequest model = new UserUpdateRequest();
		model.setUsername("joedoe");
		model.setEmail("joedoe@metaloom.io");
		model.setFirstname("Joe");
		model.setLastname("Doe");
		model.setMeta(meta());
		return model;
	}

	default UserCreateRequest userCreateRequest() {
		UserCreateRequest model = new UserCreateRequest();
		model.setUsername("joedoe");
		model.setEmail("joedoe@metaloom.io");
		model.setFirstname("Joe");
		model.setLastname("Doe");
		model.setMeta(meta());
		return model;
	}

	default UserReference userReference() {
		UserReference model = new UserReference();
		model.setUuid(uuidA());
		model.setName("joedoe");
		return model;
	}

	default UserListResponse userListResponse() {
		UserListResponse model = new UserListResponse();
		model.setMetainfo(pagingInfo());
		model.add(userResponse());
		model.add(userResponse());
		return model;
	}
}
