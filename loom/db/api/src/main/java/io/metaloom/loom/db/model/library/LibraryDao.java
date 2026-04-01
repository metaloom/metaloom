package io.metaloom.loom.db.model.library;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface LibraryDao extends CRUDDao<Library> {

	default Library createLibrary(User user, String name) {
		return createLibrary(user.getUuid(), name);
	}

	Library createLibrary(UUID uuid, String name);

}
