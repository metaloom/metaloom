package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.library.LibraryAssetRequest;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;

public interface LibraryMethods {

	LoomClientRequest<LibraryResponse> loadLibrary(UUID uuid);

	LoomClientRequest<LibraryResponse> createLibrary(LibraryCreateRequest request);

	LoomClientRequest<LibraryResponse> updateLibrary(UUID uuid, LibraryUpdateRequest request);

	LoomClientRequest<LibraryListResponse> listLibraries();

	LoomClientRequest<NoResponse> deleteLibrary(UUID uuid);

	/**
	 * Add an asset to the library.
	 *
	 * <p>
	 * Writes the organizational membership only - it neither creates nor moves a binary. Answers 201 for a new membership and 200 when the asset was
	 * already a member.
	 * </p>
	 *
	 * @param libraryUuid
	 * @param request
	 * @return
	 */
	LoomClientRequest<LibraryResponse> addLibraryAsset(UUID libraryUuid, LibraryAssetRequest request);

	default LoomClientRequest<LibraryResponse> addLibraryAsset(UUID libraryUuid, UUID assetUuid) {
		return addLibraryAsset(libraryUuid, new LibraryAssetRequest().setAssetUuid(assetUuid));
	}

	LoomClientRequest<NoResponse> removeLibraryAsset(UUID libraryUuid, UUID assetUuid);

	LoomClientRequest<AssetListResponse> listLibraryAssets(UUID libraryUuid);

	/**
	 * List the libraries the asset belongs to.
	 *
	 * @param assetUuid
	 * @return
	 */
	LoomClientRequest<LibraryListResponse> listAssetLibraries(UUID assetUuid);
}
