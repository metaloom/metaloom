package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.auth.AuthLoginRequest;

public interface LoginModelValidator extends ModelValidator {

	default void validate(AuthLoginRequest request) {
		requireNonNullOrEmpty(request.getUsername(), "A username must be specified");
		requireNonNullOrEmpty(request.getPassword(), "A password must be specified");
	}

}
