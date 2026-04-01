package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingUpdateRequest;

public interface EmbeddingModelValidator  extends ModelValidator {

	
	default void validate(EmbeddingUpdateRequest request) {

	}

	default void validate(EmbeddingResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(EmbeddingCreateRequest request) {

	}
}
