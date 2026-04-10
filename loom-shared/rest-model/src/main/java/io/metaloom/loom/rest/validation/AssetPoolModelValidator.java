package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.pool.AssetPoolCreateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolUpdateRequest;

public interface AssetPoolModelValidator extends ModelValidator {

	default void validate(AssetPoolUpdateRequest request) {

	}

	default void validate(AssetPoolResponse response) {
		requireNonNull(response, "No valid response was provided.");
		validateCreatorEditorResponse(response);
		requireNonNull(response.getName(), "A name must be set");
	}

	default void validate(AssetPoolCreateRequest request) {
		requireNonNull(request, "No valid request was provided.");
		requireNonNull(request.getName(), "A name must be set");
	}
}
