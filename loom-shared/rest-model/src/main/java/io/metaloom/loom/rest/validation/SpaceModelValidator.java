package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.space.SpaceCreateRequest;
import io.metaloom.loom.rest.model.space.SpaceResponse;
import io.metaloom.loom.rest.model.space.SpaceUpdateRequest;

public interface SpaceModelValidator extends ModelValidator {

	default void validate(SpaceUpdateRequest request) {

	}

	default void validate(SpaceResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getName(), "A space name must be set");
	}

	default void validate(SpaceCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A space name must be set");
	}
}
