package io.metaloom.loom.db.model.perm;

import java.util.UUID;

public interface PermissionDao {

	void grantUserPermission(UUID userUuid, Permission perm);
	
	void grantUserPermission(UUID userUuid, Permission perm, String resource);
	
	void grantRolePermission(UUID roleUuid, Permission perm);
	
	void grantRolePermission(UUID roleUuid, Permission perm, String resource);

	ResourcePermissionSet loadPermissionsForUser(UUID userUuid);

}
