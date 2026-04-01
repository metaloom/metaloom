package io.metaloom.loom.db.model.role;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

public interface RoleDao extends CRUDDao<Role> {

	/**
	 * Create and persist a new role.
	 * 
	 * @param creatorUuid
	 * @param name
	 * @return
	 */
	Role createRole(UUID creatorUuid, String name);

	/**
	 * Load the role by name.
	 * 
	 * @param name
	 * @return
	 */
	Role loadByName(String name);

}
