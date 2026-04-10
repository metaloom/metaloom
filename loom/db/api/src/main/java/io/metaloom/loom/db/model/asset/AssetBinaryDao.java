package io.metaloom.loom.db.model.asset;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

public interface AssetBinaryDao extends CRUDDao<AssetBinary> {

	AssetBinary createAssetBinary(String path, UUID assetUuid, UUID creatorUuid, UUID libraryUuid);

	AssetBinary loadByAssetUuid(UUID assetUuid);

	void deleteByAssetUuid(UUID assetUuid);

}
