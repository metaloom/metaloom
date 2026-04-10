package io.metaloom.loom.db.model.space;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface SpaceDao extends CRUDDao<Space> {

	default Space createSpace(User user, String name) {
		return createSpace(user.getUuid(), name);
	}

	Space createSpace(UUID userUuid, String name);

}
