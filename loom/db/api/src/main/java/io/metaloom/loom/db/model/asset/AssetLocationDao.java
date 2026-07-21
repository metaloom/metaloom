package io.metaloom.loom.db.model.asset;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

public interface AssetLocationDao extends CRUDDao<AssetLocation> {

	AssetLocation createAssetLocation(String path, UUID assetUuid, UUID creatorUuid, UUID libraryUuid);

	/**
	 * Load all locations which reference the given asset.
	 *
	 * @param assetUuid
	 * @return
	 */
	List<AssetLocation> findForAsset(UUID assetUuid);

}
