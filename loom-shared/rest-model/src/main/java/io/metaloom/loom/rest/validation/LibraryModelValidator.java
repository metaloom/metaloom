package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;

public interface LibraryModelValidator extends ModelValidator {

	default void validate(LibraryUpdateRequest request) {

	}

	default void validate(LibraryResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getName(), "A library name must be set");
	}

	default void validate(LibraryCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A library name must be set");
	}
}
