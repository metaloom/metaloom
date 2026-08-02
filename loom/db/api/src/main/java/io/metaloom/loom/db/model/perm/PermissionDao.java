package io.metaloom.loom.db.model.perm;

import java.util.UUID;

public interface PermissionDao {

	void grantUserPermission(UUID userUuid, Permission perm);

	void grantUserPermission(UUID userUuid, Permission perm, String resource);

	/**
	 * Grant a permission to a role. The grant is global - Loom has no per-object permissions, so {@code READ_ASSET} grants read access to every
	 * asset. Granting the same permission twice is a no-op rather than a primary key violation.
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

	ResourcePermissionSet loadPermissionsForUser(UUID userUuid);

}
