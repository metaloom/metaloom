package io.metaloom.loom.db.model.collection;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface CollectionDao extends CRUDDao<Collection> {

	default Collection createCollection(User user, String name) {
		return createCollection(user.getUuid(), name);
	}

	Collection createCollection(UUID userUuid, String name);

	default void link(Collection collection, Asset asset) {
		linkAsset(collection.getUuid(), asset.getUuid());
	}

	/**
	 * Add the asset to the collection.
	 *
	 * <p>
	 * Idempotent: {@code collection_asset} is keyed on {@code (collection_uuid, asset_uuid)}, so re-linking an asset that is already a member is a
	 * no-op rather than a duplicate-key error. A pipeline that assigns the same corpus twice is the normal case, not an exceptional one.
	 * </p>
	 *
	 * @param collectionUuid
	 * @param assetUuid
	 */
	void linkAsset(UUID collectionUuid, UUID assetUuid);

	default void unlink(Collection collection, Asset asset) {
		unlinkAsset(collection.getUuid(), asset.getUuid());
	}

	void unlinkAsset(UUID collectionUuid, UUID assetUuid);

	/**
	 * Return whether the asset is a member of the collection.
	 *
	 * @param collectionUuid
	 * @param assetUuid
	 * @return
	 */
	boolean containsAsset(UUID collectionUuid, UUID assetUuid);

	/**
	 * Load a page of the collections the asset belongs to.
	 *
	 * @param assetUuid
	 * @param fromId
	 * @param pageSize
	 * @return
	 */
	Page<Collection> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize);

	/**
	 * Return the number of assets in the collection.
	 *
	 * @param collectionUuid
	 * @return
	 */
	long countAssets(UUID collectionUuid);

}
