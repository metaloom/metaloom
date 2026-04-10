package io.metaloom.loom.db.model.pool;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

public interface AssetPoolDao extends CRUDDao<AssetPool> {

	AssetPool createAssetPool(UUID creatorUuid, String name);

}
