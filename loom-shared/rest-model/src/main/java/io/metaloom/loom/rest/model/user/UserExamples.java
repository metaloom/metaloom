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

	default Example userAvatarResponseExample() {
		return new ExampleImpl(userAvatarResponse(), "The user avatar response", HttpResponseStatus.OK);
	}

	default UserResponse userResponse() {
		UserResponse model = new UserResponse();
		model.setUuid(uuidA());
		model.setUsername("joedoe");
		model.setEmail("joedoe@metaloom.io");
		model.setFirstname("Joe");
		model.setLastname("Doe");
		// The /users form even when the caller read this through /me: the URL is rendered in other people's browsers too.
		model.setAvatarUrl("/api/v1/users/" + uuidA() + "/avatar/data");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default UserAvatarResponse userAvatarResponse() {
		UserAvatarResponse model = new UserAvatarResponse();
		model.setUuid(uuidB());
		model.setFilename("joedoe.jpg");
		model.setMimeType("image/jpeg");
		model.setSize(48213);
		model.setUrl("/api/v1/users/" + uuidA() + "/avatar/data");
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
