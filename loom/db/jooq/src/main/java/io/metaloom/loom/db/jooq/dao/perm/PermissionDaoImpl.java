package io.metaloom.loom.db.jooq.dao.perm;

import static io.metaloom.loom.db.jooq.tables.JooqRoleGroup.ROLE_GROUP;
import static io.metaloom.loom.db.jooq.tables.JooqRolePermission.ROLE_PERMISSION;
import static io.metaloom.loom.db.jooq.tables.JooqTokenPermission.TOKEN_PERMISSION;
import static io.metaloom.loom.db.jooq.tables.JooqUserGroup.USER_GROUP;
import static io.metaloom.loom.db.jooq.tables.JooqUserPermission.USER_PERMISSION;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;

import io.metaloom.loom.db.jooq.enums.JooqLoomPermission;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.perm.ResourcePermission;
import io.metaloom.loom.db.model.perm.ResourcePermissionSet;

@Singleton
public class PermissionDaoImpl implements PermissionDao {

	private final DSLContext ctx;

	@Inject
	public PermissionDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public ResourcePermissionSet loadPermissionsForUser(UUID userUuid) {
		// Fetch permissions for effective roles which belong to the user.
		// role_permission has no resource column (V2.64), so these grants carry a null resource.
		// That changes nothing for authorization: LoomAuthorizationProvider only ever reads
		// getPermission(). Probe role grants by permission alone.
		List<ResourcePermission> rolePerms = ctx.select(ROLE_PERMISSION.PERMISSION)
			.from(ROLE_PERMISSION)
			.leftJoin(ROLE_GROUP)
			.on(ROLE_PERMISSION.ROLE_UUID.eq(ROLE_GROUP.ROLE_UUID))
			.leftJoin(USER_GROUP)
			.on(USER_GROUP.GROUP_UUID.eq(ROLE_GROUP.GROUP_UUID))
			.where(USER_GROUP.USER_UUID.eq(userUuid))
			.fetchInto(ResourcePermission.class);

		// Fetch dedicated user permissions
		List<ResourcePermission> userPerms = ctx.select()
			.from(USER_PERMISSION)
			.where(USER_PERMISSION.USER_UUID.eq(userUuid))
			.fetchInto(ResourcePermission.class);

		// Return the combined perms
		ResourcePermissionSet permSet = new ResourcePermissionSet();
		permSet.addAll(rolePerms);
		permSet.addAll(userPerms);
		return permSet;
	}

	public ResourcePermissionSet loadPermissionsForToken(UUID tokenUUID) {
		return ctx.select(TOKEN_PERMISSION).where(TOKEN_PERMISSION.TOKEN_UUID.eq(tokenUUID)).fetchOneInto(ResourcePermissionSet.class);
		// List<ResourcePermission> tokenPerms = fetchByJooqTokenUuid(tokenUUID).stream().map(perm -> {
		// return new ResourcePermission().setPermission(perm.getPermission().name()).setResource(perm.getResource());
		// }).collect(Collectors.toList());
		// ResourcePermissionSet permSet = new ResourcePermissionSet();
		// permSet.addAll(tokenPerms);
		// return permSet;
	}

	@Override
	public void grantRolePermission(UUID roleUuid, Permission perm) {
		Objects.requireNonNull(roleUuid, "A valid role uuid must be specified to grant a permission");
		Objects.requireNonNull(perm, "A valid permission must be specified");
		// The grain of role_permission is (role_uuid, permission) - see V2.64. Re-granting an
		// existing permission is idempotent rather than a primary key violation.
		ctx.insertInto(ROLE_PERMISSION, ROLE_PERMISSION.ROLE_UUID, ROLE_PERMISSION.PERMISSION)
			.values(roleUuid, JooqLoomPermission.valueOf(perm.name()))
			.onConflict(ROLE_PERMISSION.ROLE_UUID, ROLE_PERMISSION.PERMISSION)
			.doNothing()
			.execute();
	}

	@Override
	public void grantUserPermission(UUID userUuid, Permission perm) {
		grantUserPermission(userUuid, perm, null);

	}

	@Override
	public void grantUserPermission(UUID userUuid, Permission perm, String resource) {
		Objects.requireNonNull(resource, "A valid resource must be specified to grant a permission");
		ctx.insertInto(USER_PERMISSION, USER_PERMISSION.USER_UUID, USER_PERMISSION.RESOURCE, USER_PERMISSION.PERMISSION)
			.values(userUuid, resource, JooqLoomPermission.valueOf(perm.name()))
			.execute();
	}
}
