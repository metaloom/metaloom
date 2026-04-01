package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.tag.TagUpdateRequest;

public interface TagModelValidator extends ModelValidator {

	default void validate(TagUpdateRequest request) {

	}

	default void validate(TagResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getName(), "The tag name was not set");
	}

	default void validate(TagCreateRequest request) {
		requireNonNullOrEmpty(request.getName(), "The tag name was not set");
		requireNonNullOrEmpty(request.getCollection(), "The tag collection was not set.");
	}
}
