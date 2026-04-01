package io.metaloom.loom.db.model.user;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

public interface UserDao extends CRUDDao<User> {

	public static final String ADMIN_USER_NAME = "admin";

	/**
	 * Create and store a new user with the given username.
	 * 
	 * @param creatorUuid
	 * @param username
	 * @return
	 */
	User createUser(UUID creatorUuid, String username);

	default User createUser(String username) {
		User admin = loadByUsername(ADMIN_USER_NAME);
		return createUser(admin.getUuid(), username);
	}

	/**
	 * Load the user by username.
	 * 
	 * @param username
	 * @return
	 */
	User loadByUsername(String username);

	default User loadAdmin() {
		return loadByUsername(ADMIN_USER_NAME);
	}

	/**
	 * Create the main admin user.
	 * 
	 * @return
	 */
	default User createAdmin() {
		UUID uuid = UUID.randomUUID();
		User admin = createUser(uuid, ADMIN_USER_NAME);
		admin.setUuid(uuid);
		return admin;
	}

}
