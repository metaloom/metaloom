package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagRatingRequest;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.tag.TagUpdateRequest;

public interface TagModelValidator extends ModelValidator {

	int TAG_RATING_MIN = 1;
	int TAG_RATING_MAX = 10;

	default void validate(TagUpdateRequest request) {

	}

	default void validate(TagRatingRequest request) {
		requireNonNull(request.getRating(), "The tag rating was not set");
		int rating = request.getRating();
		if (rating < TAG_RATING_MIN || rating > TAG_RATING_MAX) {
			throw new ValidationException("The tag rating must be within " + TAG_RATING_MIN + " and " + TAG_RATING_MAX);
		}
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
