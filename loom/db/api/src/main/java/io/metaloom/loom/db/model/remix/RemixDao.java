package io.metaloom.loom.db.model.remix;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

/**
 * DAO for remixes - named groups of assets that are versions of one another
 * ({@code spec/features/remix/REMIX.md}).
 *
 * <p>
 * The membership half mirrors {@code CollectionDao} deliberately: both are "assets belong to a
 * named container" relations, and a caller that knows one should not have to learn the other. The
 * differences are the {@link RemixRole} carried per member and {@link #setSource(UUID, UUID)}, which
 * has no counterpart on a collection.
 * </p>
 *
 * <p>
 * Cascades, all declared in {@code V2.100}: deleting a remix removes its member rows; deleting an
 * asset removes its memberships and nulls {@code remix.source_asset_uuid} rather than taking the
 * whole remix with it.
 * </p>
 */
public interface RemixDao extends CRUDDao<Remix> {

	default Remix createRemix(User user, String name) {
		return createRemix(user.getUuid(), name);
	}

	/** Create a transient remix; not persisted until {@code store(...)}. */
	Remix createRemix(UUID userUuid, String name);

	default void link(Remix remix, Asset asset, RemixRole role) {
		linkAsset(remix.getUuid(), asset.getUuid(), role, null, remix.getEditorUuid());
	}

	/**
	 * Add the asset to the remix.
	 *
	 * <p>
	 * Idempotent on {@code (remix_uuid, asset_uuid)}: re-adding an asset that is already a member
	 * updates its role and ordinal rather than raising a duplicate-key error, so a caller that
	 * re-submits a selection does not have to diff it first.
	 * </p>
	 *
	 * <p>
	 * Adding a second {@link RemixRole#SOURCE} is rejected by the database
	 * ({@code remix_member_single_source}). Use {@link #setSource(UUID, UUID)} to move the source
	 * instead, which demotes the incumbent in the same transaction.
	 * </p>
	 *
	 * @param remixUuid
	 * @param assetUuid
	 * @param role
	 *            {@code null} is treated as {@link RemixRole#DERIVED}
	 * @param ordinal
	 *            position within the remix, or {@code null} to sort last
	 * @param actorUuid
	 *            who added it; recorded on first insert and preserved on re-add
	 */
	void linkAsset(UUID remixUuid, UUID assetUuid, RemixRole role, Integer ordinal, UUID actorUuid);

	default void unlink(Remix remix, Asset asset) {
		unlinkAsset(remix.getUuid(), asset.getUuid());
	}

	/**
	 * Remove the asset from the remix.
	 *
	 * <p>
	 * Removing the SOURCE member also clears {@code remix.source_asset_uuid}, so the denormalised
	 * pointer never outlives the membership it mirrors.
	 * </p>
	 */
	void unlinkAsset(UUID remixUuid, UUID assetUuid);

	/** Whether the asset is a member of the remix. */
	boolean containsAsset(UUID remixUuid, UUID assetUuid);

	/**
	 * Load a page of the remix's members in insertion order, {@code (created, uuid)} ascending.
	 *
	 * <p>
	 * Insertion order rather than "source first, then ordinal" on purpose: those would need a cursor
	 * over a computed boolean and a nullable int with mixed sort directions, which keyset paging
	 * cannot express as a single row comparison. Both fields are on every {@link RemixMember} and the
	 * source is also on {@link Remix#getSourceAssetUuid()}, so a caller holding a page can order it
	 * for display without the DAO promising an order it cannot page.
	 * </p>
	 *
	 * @param remixUuid
	 * @param fromId
	 *            seek cursor - the membership uuid ending the previous page, or {@code null}. A
	 *            cursor whose row is gone yields an empty page rather than restarting from the top.
	 * @param pageSize
	 */
	Page<RemixMember> loadMembers(UUID remixUuid, UUID fromId, int pageSize);

	/** Load a page of the remixes the asset belongs to. */
	Page<Remix> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize);

	/** Number of assets in the remix. */
	long countAssets(UUID remixUuid);

	/**
	 * Make the given asset the source of the remix.
	 *
	 * <p>
	 * One transaction: demote any incumbent SOURCE member to {@link RemixRole#DERIVED}, promote the
	 * named member, and update {@code remix.source_asset_uuid} to match. The asset must already be a
	 * member. Passing {@code null} for the asset clears the source, leaving every member DERIVED.
	 * </p>
	 *
	 * @throws IllegalArgumentException
	 *             if the asset is not a member of the remix
	 */
	void setSource(UUID remixUuid, UUID assetUuid);

}
