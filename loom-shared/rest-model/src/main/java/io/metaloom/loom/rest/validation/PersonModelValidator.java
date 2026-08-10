package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonImageImportRequest;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;

public interface PersonModelValidator extends ModelValidator {

	default void validate(PersonUpdateRequest request) {

	}

	default void validate(PersonImageImportRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getDetectionUuid(), "A detection uuid must be set");
	}

	/**
	 * No required field: a blank or absent {@code imageUuid} is how the avatar is cleared.
	 */
	default void validate(PersonAvatarRequest request) {
		requireNonNull(request, "A valid request must be specified");
	}

	default void validate(PersonResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getAlias(), "A person alias must be set");
	}

	default void validate(PersonCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getAlias(), "A person alias must be set");
	}
}
