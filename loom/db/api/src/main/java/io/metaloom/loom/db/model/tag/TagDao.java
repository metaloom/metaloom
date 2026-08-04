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

	/**
	 * Persist the tag on its natural key: insert it when <code>(name, collection)</code> is free, and otherwise resolve the tag that already carries
	 * that key.
	 *
	 * <p>
	 * A tag is a <strong>global</strong> object - <code>tag</code> has a <code>UNIQUE (name, collection)</code> index - so the same name attached to a
	 * second asset must reuse the first row rather than insert a new one. {@link #store(io.metaloom.loom.db.Element)} cannot do that: it is an
	 * unconditional INSERT and hits the constraint. Anything that tags more than one asset (a human tagging a selection, a pipeline node tagging a
	 * library) has to come through here.
	 * </p>
	 *
	 * <p>
	 * Resolving <em>never overwrites</em> what the existing tag carries. Only a value this call actually supplies is written, so a shared tag keeps
	 * its meta, rating, colour and its original creator - a worker attaching an existing tag is not a curator of it.
	 * </p>
	 *
	 * @param tag
	 *            the transient tag; its uuid and persisted values are populated on return
	 * @return the uuid of the inserted or resolved row
	 */
	UUID resolveOrCreateAssetTag(AssetTag tag);

	/**
	 * Attach the tag to the asset, or update the region of an existing attachment.
	 *
	 * <p>
	 * Idempotent: <code>tag_asset</code> is keyed <code>(tag_uuid, asset_uuid)</code> and re-running a pipeline over an asset it has already tagged is
	 * the normal case, not an error.
	 * </p>
	 */
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
