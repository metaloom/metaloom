package io.metaloom.loom.db.model.blacklist;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;

public interface BlacklistDao extends CRUDDao<Blacklist> {

	default Blacklist createBlacklist(User user, Asset asset, String name) {
		return createBlacklist(user.getUuid(), asset.getUuid(), name);
	}

	Blacklist createBlacklist(UUID userUuid, UUID assetUUID, String name);

}
