package io.metaloom.loom.db.model.tag;

import java.util.Collection;
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

	/**
	 * Apply a whole set of tags to one asset, and detach the named attachments, in a single transaction.
	 *
	 * <p>
	 * The bulk counterpart to {@link #resolveOrCreateAssetTag(AssetTag)} + {@link #tagAsset(AssetTag, Asset)} + {@link #untagAsset(Tag, Asset)}. It
	 * exists for scale: a node attaching five tags across a hundred thousand assets otherwise issues half a million round trips, each its own
	 * transaction. Each tag is still resolved on its natural key, so an existing tag is attached rather than duplicated.
	 * </p>
	 *
	 * <p>
	 * Withdrawal is by tag uuid and is <em>not</em> a desired-set operation: only the attachments the caller names are removed, never "everything
	 * else". <code>tag_asset</code> carries no provenance, so the database cannot distinguish a tag a worker wrote from one a person typed, and a
	 * desired-set semantic here would let a worker delete human curation.
	 * </p>
	 *
	 * @param asset
	 *            the asset to tag
	 * @param tags
	 *            the tags to attach; each element's uuid and persisted values are populated on return
	 * @param withdraw
	 *            uuids of tags to detach from this asset, or <code>null</code>. The tags themselves are not deleted
	 * @param withdrawNodeId
	 *            when set, only placements written by that pipeline node are detached. 🔴 A node must pass its own id: since V2.71 a tag can sit on an
	 *            asset several times, so withdrawing by tag alone would take a person's placement of the same name along with the node's. A person -
	 *            <code>null</code> here - removes every placement, which is what an untag means when a human asks for it
	 * @return the applied tags and the number of placements actually removed
	 */
	AssetTagBulkResult bulkTagAsset(Asset asset, List<AssetTag> tags, Collection<UUID> withdraw, String withdrawNodeId);

	/** Bulk write with no withdrawal restriction - every placement of a named tag comes off. */
	default AssetTagBulkResult bulkTagAsset(Asset asset, List<AssetTag> tags, Collection<UUID> withdraw) {
		return bulkTagAsset(asset, tags, withdraw, null);
	}

	/**
	 * The outcome of {@link #bulkTagAsset(Asset, List, Collection)}.
	 *
	 * @param applied
	 *            the tags now attached, carrying the uuid and the persisted values of the resolved rows
	 * @param withdrawn
	 *            how many attachments were removed - never more than the caller asked for, and fewer when a named tag was not attached
	 */
	record AssetTagBulkResult(List<AssetTag> applied, int withdrawn) {
	}

	/**
	 * Remove <strong>every</strong> placement of the tag on the asset.
	 *
	 * <p>
	 * A tag placed on three faces of one photo comes off all three - "remove this tag from this picture" is what the caller of an untag route asks for.
	 * To remove one region and keep the others, use {@link #removePlacement(Asset, UUID)}.
	 * </p>
	 */
	void untagAsset(Tag tag, Asset asset);

	/**
	 * Remove one placement by its own uuid, leaving every other placement of the same tag on the asset alone.
	 *
	 * <p>
	 * The asset is part of the condition rather than a convenience: the placement uuid on its own would let a caller permitted to edit one asset detach
	 * a tag from another.
	 * </p>
	 *
	 * @return <code>true</code> when a row was removed; <code>false</code> when the placement does not exist or belongs to a different asset
	 */
	boolean removePlacement(Asset asset, UUID placementUuid);

	/**
	 * The placements on this asset written by one pipeline node instance.
	 *
	 * <p>
	 * This is what makes reconciliation an operation the server can answer since V2.71: "which tags on this asset are mine". Before the join row
	 * carried {@code node_id}, a node had to read back a component it wrote itself to know that.
	 * </p>
	 */
	List<AssetTag> assetTagsByNode(Asset asset, String nodeId);

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
