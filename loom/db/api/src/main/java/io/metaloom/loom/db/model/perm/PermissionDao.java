package io.metaloom.loom.db.model.perm;

import java.util.UUID;

/**
 * Grants and effective permission sets.
 *
 * <p>
 * Every grant is global - Loom has no per-object permissions, so {@code READ_ASSET} grants read access to every asset. The grain of all three grant
 * tables is (subject, permission) (V2.64 for roles, V2.118 for users and tokens), so granting a permission the subject already holds is a no-op rather
 * than a primary key violation, and a subject may hold any number of grants.
 * </p>
 */
public interface PermissionDao {

	/**
	 * Grant a permission directly to a user, beside whatever reaches them through group membership.
	 *
	 * @param userUuid
	 * @param perm
	 */
	void grantUserPermission(UUID userUuid, Permission perm);

	/**
	 * Grant a permission to a role.
	 *
	 * <p>
	 * Roles reach users through group membership only ({@code user -> user_group -> group -> role_group -> role}); a grant on a role which is not
	 * linked to a group confers nothing.
	 * </p>
	 *
	 * @param roleUuid
	 * @param perm
	 */
	void grantRolePermission(UUID roleUuid, Permission perm);

	/**
	 * Grant a permission to an API token.
	 *
	 * @param tokenUuid
	 * @param perm
	 */
	void grantTokenPermission(UUID tokenUuid, Permission perm);

	/**
	 * The user's effective permissions: their direct grants plus every grant of every role reaching them through a group.
	 *
	 * @param userUuid
	 * @return
	 */
	ResourcePermissionSet loadPermissionsForUser(UUID userUuid);

	/**
	 * The permissions granted to a token.
	 *
	 * @param tokenUuid
	 * @return The grants; empty when the token holds none or does not exist.
	 */
	ResourcePermissionSet loadPermissionsForToken(UUID tokenUuid);

}
