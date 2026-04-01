package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.collection.CollectionUpdateRequest;

public interface CollectionModelValidator extends ModelValidator {

	
	default void validate(CollectionUpdateRequest request) {

	}

	default void validate(CollectionResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(CollectionCreateRequest request) {

	}
}
