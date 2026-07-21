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

	// TAG - USER RATING (tag_user_meta)

	default void storeUserRating(UUID tagUuid, User user, int rating) {
		storeUserRating(tagUuid, user.getUuid(), rating);
	}

	/**
	 * Upsert the rating for the given tag and user in the <code>tag_user_meta</code> relation.
	 *
	 * @param tagUuid
	 * @param userUuid
	 * @param rating
	 */
	void storeUserRating(UUID tagUuid, UUID userUuid, int rating);

	default Integer readUserRating(UUID tagUuid, User user) {
		return readUserRating(tagUuid, user.getUuid());
	}

	/**
	 * Load the rating which the given user assigned to the tag or <code>null</code> when no rating was stored.
	 *
	 * @param tagUuid
	 * @param userUuid
	 * @return
	 */
	Integer readUserRating(UUID tagUuid, UUID userUuid);

	default void deleteUserRating(UUID tagUuid, User user) {
		deleteUserRating(tagUuid, user.getUuid());
	}

	/**
	 * Remove the user's rating for the tag.
	 *
	 * @param tagUuid
	 * @param userUuid
	 */
	void deleteUserRating(UUID tagUuid, UUID userUuid);

}
