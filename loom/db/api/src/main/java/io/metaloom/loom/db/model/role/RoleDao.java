package io.metaloom.loom.db.model.role;

import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.perm.Permission;

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

	/**
	 * Load the permissions the role grants.
	 *
	 * @param roleUuid
	 * @return The granted permissions; empty when the role grants nothing or does not exist.
	 */
	Set<Permission> loadPermissions(UUID roleUuid);

	/**
	 * Replace the permissions the role grants.
	 *
	 * <p>
	 * Replace semantics: after the call the role grants exactly the given set. Permissions which are absent from it are revoked, permissions which are
	 * new are granted, and permissions already present are left untouched. An empty set revokes everything. Both statements run in one transaction, so
	 * the role is never observed with a partially applied set.
	 * </p>
	 *
	 * @param roleUuid
	 * @param permissions
	 *            The full set of permissions the role should grant. Must not be null - pass an empty set to revoke all.
	 */
	void setPermissions(UUID roleUuid, Set<Permission> permissions);

}
