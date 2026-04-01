package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.asset.location.AssetLocationCreateRequest;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationUpdateRequest;

public interface AssetLocationModelValidator extends ModelValidator {

	default void validate(AssetLocationUpdateRequest request) {

	}

	default void validate(AssetLocationResponse response) {
		requireNonNull(response, "No valid request was provided.");
		validateCreatorEditorResponse(response);
		requireNonNull(response.getAssetUuid(), "A assetUuid must be set");
		requireNonNull(response.getLibraryUuid(), "A libraryUuid must be set");
	}

	default void validate(AssetLocationCreateRequest request) {
		requireNonNull(request, "No valid request was provided.");
		requireNonNull(request.getAssetUuid(), "A assetUuid must be set");
		requireNonNull(request.getLibraryUuid(), "A libraryUuid must be set");
	}
}
