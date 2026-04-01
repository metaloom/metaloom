package io.metaloom.loom.db.user;

import java.util.UUID;

import io.metaloom.loom.db.mem.AbstractMemDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.utils.UUIDUtils;

public class MemUsersDaoImpl extends AbstractMemDao<User> implements UserDao {

	@Override
	public User loadByUsername(String username) {
		return storage.values().stream().filter(u -> u.getUsername().equals(username)).findFirst().get();
	}

	@Override
	public String getTypeName() {
		return "Users";
	}

	@Override
	public User createUser(UUID creatorUuid, String username) {
		User user = new MemUserImpl();
		user.setUuid(UUIDUtils.randomUUID());
		user.setUsername(username);
		return user;
	}

}
