package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.comment.CommentUpdateRequest;

public interface CommentModelValidator extends ModelValidator {

	default void validate(CommentUpdateRequest request) {

	}

	default void validate(CommentResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(CommentCreateRequest request) {
		requireNonNull(request.getTitle(), "The title must be set.");
		requireNonNull(request.getText(), "The text must be set.");
	}
}
