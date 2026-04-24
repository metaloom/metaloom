package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.blacklist.BlacklistCreateRequest;
import io.metaloom.loom.rest.model.blacklist.BlacklistResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistUpdateRequest;

public interface BlacklistModelValidator extends ModelValidator {

	default void validate(BlacklistUpdateRequest request) {

	}

	default void validate(BlacklistResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(BlacklistCreateRequest request) {
		requireNonNull(request.getName(), "The name must be set.");
	}
}
