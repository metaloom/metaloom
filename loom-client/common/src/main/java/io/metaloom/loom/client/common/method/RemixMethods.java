package io.metaloom.loom.client.common.method;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.remix.RemixCreateRequest;
import io.metaloom.loom.rest.model.remix.RemixListResponse;
import io.metaloom.loom.rest.model.remix.RemixMemberListResponse;
import io.metaloom.loom.rest.model.remix.RemixMemberRequest;
import io.metaloom.loom.rest.model.remix.RemixResponse;
import io.metaloom.loom.rest.model.remix.RemixUpdateRequest;

/**
 * Remixes - named groups of assets that are versions of one another.
 */
public interface RemixMethods {

	LoomClientRequest<RemixResponse> loadRemix(UUID remixUuid);

	/**
	 * Create a remix. The request may carry its members, so "combine these into a remix" is one call
	 * rather than a create followed by an add that can fail on its own.
	 */
	LoomClientRequest<RemixResponse> createRemix(RemixCreateRequest request);

	default LoomClientRequest<RemixResponse> createRemix(String name, UUID... assetUuids) {
		RemixCreateRequest request = new RemixCreateRequest().setName(name);
		for (UUID assetUuid : assetUuids) {
			request.add(assetUuid);
		}
		return createRemix(request);
	}

	LoomClientRequest<RemixResponse> updateRemix(UUID remixUuid, RemixUpdateRequest request);

	LoomClientRequest<RemixListResponse> listRemixes();

	LoomClientRequest<NoResponse> deleteRemix(UUID remixUuid);

	/**
	 * Add one or more assets to the remix. Adding an asset that is already a member rewrites its
	 * membership rather than failing, so a re-submitted selection needs no diffing.
	 */
	LoomClientRequest<RemixResponse> addRemixAssets(UUID remixUuid, RemixMemberRequest request);

	default LoomClientRequest<RemixResponse> addRemixAssets(UUID remixUuid, List<UUID> assetUuids) {
		return addRemixAssets(remixUuid, new RemixMemberRequest().setAssetUuids(assetUuids));
	}

	LoomClientRequest<NoResponse> removeRemixAsset(UUID remixUuid, UUID assetUuid);

	LoomClientRequest<RemixMemberListResponse> listRemixAssets(UUID remixUuid);

	/**
	 * Make a member asset the source of the remix. The previous source is demoted in the same
	 * transaction; naming a non-member is a 400.
	 */
	LoomClientRequest<RemixResponse> setRemixSource(UUID remixUuid, UUID assetUuid);

	/** List the remixes the asset belongs to. */
	LoomClientRequest<RemixListResponse> listAssetRemixes(UUID assetUuid);

}
