package io.metaloom.loom.rest.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.rest.model.remix.RemixCreateRequest;
import io.metaloom.loom.rest.model.remix.RemixMemberRequest;
import io.metaloom.loom.rest.model.remix.RemixResponse;
import io.metaloom.loom.rest.model.remix.RemixUpdateRequest;

public interface RemixModelValidator extends ModelValidator {

	default void validate(RemixCreateRequest request) {
		requireNonNullOrEmpty(request.getName(), "A remix name must be set");
		validateMembers(request.getAssetUuids(), request.getSourceAssetUuid(), true);
	}

	default void validate(RemixUpdateRequest request) {
		if (request.getName() != null) {
			requireNonNullOrEmpty(request.getName(), "A remix name must not be blank");
		}
	}

	default void validate(RemixMemberRequest request) {
		requireNonNull(request.getAssetUuids(), "A list of asset uuids must be provided");
		if (request.getAssetUuids().isEmpty()) {
			throw new ValidationException("At least one asset uuid must be provided");
		}
		validateMembers(request.getAssetUuids(), request.getSourceAssetUuid(), false);
	}

	default void validate(RemixResponse response) {
		validateCreatorEditorResponse(response);
	}

	/**
	 * A member list must not repeat an asset, and a named source must be in it.
	 *
	 * <p>
	 * Both are caught here rather than in the database. The duplicate would be swallowed silently by
	 * the idempotent insert, leaving the caller believing it added more than it did; the stray source
	 * would surface as a 500 out of the DAO rather than as the 400 it is.
	 * </p>
	 *
	 * @param sourceMustBeListed
	 *            true when the request also supplies the members, so a source outside them cannot be
	 *            an already-stored member
	 */
	private void validateMembers(List<UUID> assetUuids, UUID sourceAssetUuid, boolean sourceMustBeListed) {
		if (assetUuids == null) {
			return;
		}
		Set<UUID> seen = new HashSet<>();
		for (UUID assetUuid : assetUuids) {
			requireNonNull(assetUuid, "An asset uuid in the list was null");
			if (!seen.add(assetUuid)) {
				throw new ValidationException("The asset " + assetUuid + " is listed more than once");
			}
		}
		if (sourceMustBeListed && sourceAssetUuid != null && !seen.contains(sourceAssetUuid)) {
			throw new ValidationException("The source asset " + sourceAssetUuid + " must be one of the listed assets");
		}
	}
}
