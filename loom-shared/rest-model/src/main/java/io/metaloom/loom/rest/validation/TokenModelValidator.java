package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.token.TokenCreateRequest;
import io.metaloom.loom.rest.model.token.TokenResponse;
import io.metaloom.loom.rest.model.token.TokenUpdateRequest;

public interface TokenModelValidator extends ModelValidator {

	default void validate(TokenUpdateRequest request) {

	}

	default void validate(TokenResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(TokenCreateRequest request) {

	}
}
