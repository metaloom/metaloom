package io.metaloom.loom.db.model.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

public interface AssetDao extends CRUDDao<Asset> {

	default Asset createAsset(User user, SHA512 sha512sum, String mimeType, String filename, String initialOrigin, long size) {
		return createAsset(user.getUuid(), sha512sum, mimeType, filename, initialOrigin, size);
	}

	Asset createAsset(UUID userUuid, SHA512 sha512sum, String mimeType, String filename, String initialOrigin, long size);

	void storeUserMeta(User user, Asset asset, JsonObject meta);

	void deleteUserMeta(User user, Asset asset);

	JsonObject readUserMeta(User user, Asset asset);

	Asset loadBySHA512(SHA512 sha512sum);

	void deleteBySHA512(SHA512 sha512sum);

	Asset loadById(AssetId assetId);

	/**
	 * Create multiple assets in bulk. Returns the list of created asset POJOs (not yet stored).
	 * Call {@link #storeBatch(List)} to persist them.
	 * 
	 * @param userUuid
	 * @param entries
	 * @return list of created asset POJOs
	 */
	default List<Asset> createAssets(UUID userUuid, List<AssetBulkEntry> entries) {
		List<Asset> assets = new ArrayList<>();
		for (AssetBulkEntry entry : entries) {
			assets.add(createAsset(userUuid, entry.sha512(), entry.mimeType(), entry.filename(), entry.initialOrigin(), entry.size()));
		}
		return assets;
	}

	/**
	 * Load a page of the assets in the collection.
	 *
	 * @param collectionUuid
	 * @param fromId
	 * @param pageSize
	 * @return
	 */
	Page<Asset> loadPageByCollection(UUID collectionUuid, UUID fromId, int pageSize);

	/**
	 * Load a page of the assets in the library.
	 *
	 * <p>
	 * This reads {@code library_asset} - the organizational membership - not {@code asset_location.library_uuid}. The two answer different questions:
	 * "which assets did a curator put in this library" versus "whose bytes were scanned under this library root".
	 * </p>
	 *
	 * @param libraryUuid
	 * @param fromId
	 * @param pageSize
	 * @return
	 */
	Page<Asset> loadPageByLibrary(UUID libraryUuid, UUID fromId, int pageSize);

}
