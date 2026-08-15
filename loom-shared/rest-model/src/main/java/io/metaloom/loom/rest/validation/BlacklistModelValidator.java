package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.blacklist.BlacklistCreateRequest;
import io.metaloom.loom.rest.model.blacklist.BlacklistResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistUpdateRequest;

public interface BlacklistModelValidator extends ModelValidator {

	default void validate(BlacklistUpdateRequest request) {

	}

	default void validate(BlacklistResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(BlacklistCreateRequest request) {
		requireNonNull(request.getName(), "The name must be set.");
		// A blacklist entry blocks one asset - the table declares asset_uuid NOT NULL and keys the entry on
		// (asset_uuid, creator_uuid). Leaving it unchecked meant a request without an asset reached the insert
		// and came back as a 500 from the constraint violation instead of a 400.
		requireNonNullOrEmpty(request.getAssetUuid(), "A assetUuid must be set");
	}
}
