package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;

public interface UserModelValidator extends ModelValidator {

	default void validate(UserUpdateRequest request) {
		requireNonNull(request, "No valid request was provided.");
	}

	default void validate(UserResponse response) {
		requireNonNull(response, "No valid request was provided.");
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getUsername(), "A valid username must be set");
	}

	default void validate(UserCreateRequest request) {
		requireNonNull(request, "No valid request was provided.");
		requireNonNullOrEmpty(request.getUsername(), "A valid username must be set");
	}
}
