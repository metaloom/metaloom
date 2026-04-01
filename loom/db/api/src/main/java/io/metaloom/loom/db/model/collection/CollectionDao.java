package io.metaloom.loom.db.model.collection;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;

public interface CollectionDao extends CRUDDao<Collection> {

	default Collection createCollection(User user, String name) {
		return createCollection(user.getUuid(), name);
	}

	Collection createCollection(UUID userUuid, String name);

	default void link(Collection collection, Asset asset) {
		linkAsset(collection.getUuid(), asset.getUuid());
	}

	void linkAsset(UUID collectionUuid, UUID assetUuid);

	default void unlink(Collection collection, Asset asset) {
		unlinkAsset(collection.getUuid(), asset.getUuid());
	}

	void unlinkAsset(UUID collectionUuid, UUID assetUuid);

}
