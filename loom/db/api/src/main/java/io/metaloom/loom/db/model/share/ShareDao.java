package io.metaloom.loom.db.model.share;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface ShareDao extends CRUDDao<Share> {

	default Share createAssetShare(User user, Asset asset, String slug) {
		return createAssetShare(user == null ? null : user.getUuid(), asset.getUuid(), slug);
	}

	/**
	 * Build an unsaved share of a single asset. Audit columns are filled from {@code userUuid}, which may be null.
	 */
	Share createAssetShare(UUID userUuid, UUID assetUuid, String slug);

	default Share createCollectionShare(User user, Collection collection, String slug) {
		return createCollectionShare(user == null ? null : user.getUuid(), collection.getUuid(), slug);
	}

	/**
	 * Build an unsaved share of a collection. Audit columns are filled from {@code userUuid}, which may be null.
	 */
	Share createCollectionShare(UUID userUuid, UUID collectionUuid, String slug);

	/**
	 * Resolve a share by the slug in its URL.
	 *
	 * <p>
	 * The hot path: every request a visitor makes starts here. Returns the row regardless of whether it has expired - expiry is a decision for the
	 * caller, because the endpoint must answer 404 for an expired link and the sweep that eventually deletes one needs to see it.
	 * </p>
	 *
	 * @param slug
	 *            the public half of the capability
	 * @return the share, or null when no such slug exists
	 */
	Share loadBySlug(String slug);

	/**
	 * Return whether a slug is already taken. Used to retry generation rather than to let the unique constraint surface as a 500.
	 */
	boolean slugExists(String slug);

	/**
	 * Load a page of the shares pointing at one asset.
	 */
	Page<Share> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize);

	/**
	 * Load a page of the shares pointing at one collection.
	 */
	Page<Share> loadPageByCollection(UUID collectionUuid, UUID fromId, int pageSize);

	/**
	 * Record a redeemed session: stamp the visitor name if this is the first visit, bump the view counter and the last-viewed timestamp.
	 *
	 * <p>
	 * One statement rather than a read-modify-write, so two visitors opening a link at the same moment cannot lose a count between them. The name is
	 * written with {@code COALESCE}, which is what makes "first visit wins" true concurrently as well as sequentially.
	 * </p>
	 *
	 * @param shareUuid
	 *            the share being opened
	 * @param visitorName
	 *            the name offered by this visitor; applied only when none is stored yet
	 */
	void recordVisit(UUID shareUuid, String visitorName);
}
