package io.metaloom.loom.db.model.tag;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;

/**
 * DAO to manage {@link Tag} elements.
 */
public interface TagDao extends CRUDDao<Tag> {

	default Tag createTag(User user, String name, String collection) {
		return createTag(user.getUuid(), name, collection);
	}

	Tag createTag(UUID userUuid, String name, String collection);

	default AssetTag createAssetTag(User user, String name, String collection) {
		return createAssetTag(user.getUuid(), name, collection);
	}

	AssetTag createAssetTag(UUID userUuid, String name, String collection);

	void tagAsset(AssetTag tag, Asset asset);

	void untagAsset(Tag tag, Asset asset);

	void tagAnnotation(Tag tag, Annotation annotation);

	void untagAnnotation(Tag tag, Annotation annotation);

	List<AssetTag> assetTags(Asset asset);

}
