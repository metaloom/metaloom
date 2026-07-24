package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

public interface JsonCompModelValidator extends ModelValidator {

	default void validate(JsonCompCreateRequest request) {
		requireNonNullOrEmpty(request.getNodeKind(), "nodeKind");
		requireNonNullOrEmpty(request.getSchemaType(), "schemaType");
	}

	default void validate(JsonCompResponse response) {

	}
}
