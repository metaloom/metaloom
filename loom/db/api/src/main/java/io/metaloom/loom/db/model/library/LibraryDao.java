package io.metaloom.loom.db.model.library;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface LibraryDao extends CRUDDao<Library> {

	default Library createLibrary(User user, String name) {
		return createLibrary(user.getUuid(), name);
	}

	Library createLibrary(UUID uuid, String name);

	default void link(Library library, Asset asset) {
		linkAsset(library.getUuid(), asset.getUuid());
	}

	/**
	 * Add the asset to the library.
	 *
	 * <p>
	 * This writes {@code library_asset} - the <b>organizational</b> membership - and is deliberately independent of {@code asset_location}, which
	 * records where the bytes physically are. An asset can belong to a library it has no binary in (a curated grouping), and the scanner writes
	 * locations without necessarily writing memberships.
	 * </p>
	 *
	 * <p>
	 * Idempotent: {@code library_asset} is keyed on {@code (library_uuid, asset_uuid)}, so re-linking is a no-op.
	 * </p>
	 *
	 * @param libraryUuid
	 * @param assetUuid
	 */
	void linkAsset(UUID libraryUuid, UUID assetUuid);

	default void unlink(Library library, Asset asset) {
		unlinkAsset(library.getUuid(), asset.getUuid());
	}

	void unlinkAsset(UUID libraryUuid, UUID assetUuid);

	/**
	 * Return whether the asset is a member of the library.
	 *
	 * @param libraryUuid
	 * @param assetUuid
	 * @return
	 */
	boolean containsAsset(UUID libraryUuid, UUID assetUuid);

	/**
	 * Load a page of the libraries the asset belongs to.
	 *
	 * @param assetUuid
	 * @param fromId
	 * @param pageSize
	 * @return
	 */
	Page<Library> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize);

	/**
	 * Return the number of assets in the library.
	 *
	 * @param libraryUuid
	 * @return
	 */
	long countAssets(UUID libraryUuid);

}
