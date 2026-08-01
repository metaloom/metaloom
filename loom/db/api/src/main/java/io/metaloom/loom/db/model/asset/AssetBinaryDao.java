package io.metaloom.loom.db.model.asset;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

/**
 * DAO over {@code asset_location} — the table the REST layer calls "binary".
 *
 * <h2>Cardinality</h2>
 *
 * <p>
 * An asset has <b>zero or more</b> locations, keyed {@code (library_uuid, path)}. {@code V2.20} briefly enforced one per asset and {@code V2.48}
 * removed that again, deliberately: the same content legitimately lives at several paths and in several libraries, which is the entire reason the
 * table is separate from {@code asset}.
 * </p>
 *
 * <p>
 * {@link #loadPrimaryByAssetUuid(UUID)} therefore replaces the old {@code loadByAssetUuid}, which used jOOQ {@code fetchOne} and threw
 * {@code TooManyRowsException} — surfacing as an HTTP 500 — the moment a second location existed. Callers that genuinely want one location want the
 * oldest one; callers that want them all use {@link #loadAllByAssetUuid(UUID)}.
 * </p>
 */
public interface AssetBinaryDao extends CRUDDao<AssetBinary> {

	AssetBinary createAssetBinary(String path, UUID assetUuid, UUID creatorUuid, UUID libraryUuid);

	/**
	 * The asset's primary location: the oldest one, tie-broken by uuid so the answer is stable across calls and across replicas.
	 *
	 * @param assetUuid
	 *            the asset
	 * @return the location, or null when the asset has none
	 */
	AssetBinary loadPrimaryByAssetUuid(UUID assetUuid);

	/**
	 * Every location of an asset, oldest first.
	 *
	 * @param assetUuid
	 *            the asset
	 * @return the locations, never null
	 */
	List<AssetBinary> loadAllByAssetUuid(UUID assetUuid);

	/**
	 * The asset's location within one library. This is the natural key of an upload: "replace the bytes this library holds for this asset".
	 *
	 * @param assetUuid
	 *            the asset
	 * @param libraryUuid
	 *            the library
	 * @return the location, or null when the asset has none in that library
	 */
	AssetBinary loadByAssetAndLibrary(UUID assetUuid, UUID libraryUuid);

	/**
	 * How many locations point at the same bytes in the same pool.
	 *
	 * <p>
	 * The question a delete has to answer before unlinking anything: storage is content-addressed and therefore shared, so removing the object
	 * because <em>one</em> row went away would blank the preview of every other asset that deduplicated onto it.
	 * </p>
	 *
	 * @param poolUuid
	 *            the pool, or null for the default local storage
	 * @param path
	 *            the locator
	 * @return the number of {@code asset_location} rows referencing it
	 */
	long countByPoolAndPath(UUID poolUuid, String path);

	void deleteByAssetUuid(UUID assetUuid);

}
