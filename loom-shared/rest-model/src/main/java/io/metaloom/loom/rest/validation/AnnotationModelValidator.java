package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.annotation.AnnotationCreateRequest;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationUpdateRequest;

public interface AnnotationModelValidator extends ModelValidator {

	default void validate(AnnotationUpdateRequest request) {

	}

	default void validate(AnnotationResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(AnnotationCreateRequest request) {
		requireNonNull(request.getType(), "The type of the annotation must be set.");
		requireNonNull(request.getTitle(), "The title of the annotation must be set.");
		requireNonNull(request.getAssetUuid(), "The asset uuid for the annotation must be set.");
	}
}
