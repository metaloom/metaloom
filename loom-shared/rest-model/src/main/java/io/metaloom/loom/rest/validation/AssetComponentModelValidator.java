package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentUpdateRequest;

public interface AssetComponentModelValidator extends ModelValidator {

	default void validate(AssetComponentCreateRequest request) {
	}

	default void validate(AssetComponentUpdateRequest request) {
	}

	default void validate(AssetComponentResponse response) {
		validateCreatorEditorResponse(response);
	}
}
