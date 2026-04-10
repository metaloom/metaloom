package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.asset.binary.AssetBinaryCreateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;

public interface AssetBinaryModelValidator extends ModelValidator {

	default void validate(AssetBinaryUpdateRequest request) {

	}

	default void validate(AssetBinaryResponse response) {
		requireNonNull(response, "No valid request was provided.");
		validateCreatorEditorResponse(response);
		requireNonNull(response.getAssetUuid(), "A assetUuid must be set");
		requireNonNull(response.getLibraryUuid(), "A libraryUuid must be set");
	}

	default void validate(AssetBinaryCreateRequest request) {
		requireNonNull(request, "No valid request was provided.");
		requireNonNull(request.getAssetUuid(), "A assetUuid must be set");
		requireNonNull(request.getLibraryUuid(), "A libraryUuid must be set");
	}
}
