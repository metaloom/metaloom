package io.metaloom.loom.db.perm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.ResourcePermission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;

/**
 * Grant/load behaviour of {@link io.metaloom.loom.db.model.perm.PermissionDao} across the bindings the DAO actually exposes: direct user grants
 * ({@code user_permission}) and role grants ({@code role_permission}) that reach a user through group membership.
 *
 * <p>
 * Everything is asserted through {@link io.metaloom.loom.db.model.perm.PermissionDao#loadPermissionsForUser(UUID)}, the only effective-permission load
 * the interface provides. It unions a user's direct grants with the role grants reachable via {@code user_group -> role_group -> role_permission}, so a
 * permission appears exactly when the grant and (for roles) the join chain are in place.
 * </p>
 *
 * <p>
 * Scope note: token-bound grants ({@code token_permission}) and permission revocation are intentionally not covered here - the DAO exposes no
 * token-grant method, no working token load ({@code loadPermissionsForToken} is unfinished and not on the interface) and no revoke API. Grant removal via
 * hard-delete cascade is covered separately by {@link io.metaloom.loom.db.jooq.dao.AclCascadeTest}.
 * </p>
 */
public class PermissionDaoTest extends AbstractJooqTest {

	private User storeUser(String username) {
		User user = userDao().createUser(username);
		userDao().store(user);
		return user;
	}

	private Role storeRole(String name) {
		Role role = roleDao().createRole(adminUser().getUuid(), name);
		roleDao().store(role);
		return role;
	}

	private Group storeGroup(String name) {
		Group group = groupDao().create(adminUser(), name);
		groupDao().store(group);
		return group;
	}

	/**
	 * {@code ResourcePermission} has no {@code equals}/{@code hashCode}, so the effective set can only be probed by iterating and matching on the
	 * (permission, resource) pair - not via {@code contains(new ResourcePermission(...))}.
	 */
	private boolean hasPermission(UUID userUuid, Permission perm, String resource) {
		for (ResourcePermission rp : permissionDao().loadPermissionsForUser(userUuid)) {
			if (perm.name().equals(rp.getPermission()) && resource.equals(rp.getResource())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Probe by permission alone. This is the correct probe for role grants: {@code role_permission} has no {@code resource} column (V2.64), so those
	 * rows load with a null resource - and no authorization decision ever reads it.
	 */
	private boolean hasPermission(UUID userUuid, Permission perm) {
		for (ResourcePermission rp : permissionDao().loadPermissionsForUser(userUuid)) {
			if (perm.name().equals(rp.getPermission())) {
				return true;
			}
		}
		return false;
	}

	private int permCount(UUID userUuid) {
		return permissionDao().loadPermissionsForUser(userUuid).size();
	}

	/**
	 * A direct user grant surfaces on the user's effective set - and only that grant. {@code user_permission} has a single-column PK
	 * ({@code user_uuid}), so a fresh user holds exactly one grant row, which lets us assert the set contents exactly.
	 */
	@Test
	public void testGrantUserPermission() {
		String resource = "asset-res";
		User user = storeUser("perm_user_grant");

		permissionDao().grantUserPermission(user.getUuid(), Permission.CREATE_ASSET, resource);

		assertEquals(1, permCount(user.getUuid()), "The fresh user must hold exactly the one direct grant");
		assertTrue(hasPermission(user.getUuid(), Permission.CREATE_ASSET, resource),
			"The granted (CREATE_ASSET, " + resource + ") permission must be loaded back");
		assertFalse(hasPermission(user.getUuid(), Permission.READ_ASSET, resource),
			"A permission that was never granted must not appear");
		assertFalse(hasPermission(user.getUuid(), Permission.CREATE_ASSET, "other-res"),
			"The grant must be scoped to its resource");
	}

	/**
	 * A role grant reaches a user through group membership: role -> role_group -> group -> user_group -> user. This is the effective-permission
	 * inheritance path.
	 */
	@Test
	public void testUserInheritsRolePermissionViaGroup() {
		Role role = storeRole("perm_role");
		Group group = storeGroup("perm_group");
		User user = storeUser("perm_role_member");

		permissionDao().grantRolePermission(role.getUuid(), Permission.READ_ASSET);
		groupDao().addRoleToGroup(group, role);
		groupDao().addUserToGroup(group, user);

		assertTrue(hasPermission(user.getUuid(), Permission.READ_ASSET),
			"The member must inherit the role's permission via the group");
	}

	/**
	 * Re-granting a permission the role already holds is a no-op, not a primary key violation. {@code RoleDao.setPermissions} relies on this to keep
	 * surviving grants in place while it inserts the new ones.
	 */
	@Test
	public void testGrantRolePermissionIsIdempotent() {
		Role role = storeRole("perm_role_idempotent");
		Group group = storeGroup("perm_group_idempotent");
		User user = storeUser("perm_role_idempotent_member");
		groupDao().addRoleToGroup(group, role);
		groupDao().addUserToGroup(group, user);

		permissionDao().grantRolePermission(role.getUuid(), Permission.READ_ASSET);
		permissionDao().grantRolePermission(role.getUuid(), Permission.READ_ASSET);

		assertTrue(hasPermission(user.getUuid(), Permission.READ_ASSET), "The grant must still be in place");
		assertEquals(1, permCount(user.getUuid()), "The second grant must not have added a second row");
	}

	/**
	 * The role grant resolves only through the join chain: a user who is not linked to the role's group does not see the permission.
	 */
	@Test
	public void testRolePermissionInvisibleWithoutGroupMembership() {
		Role role = storeRole("detached_role");
		permissionDao().grantRolePermission(role.getUuid(), Permission.READ_ASSET);

		// The role's grant exists, but nothing links it to this user.
		User outsider = storeUser("perm_outsider");

		assertFalse(hasPermission(outsider.getUuid(), Permission.READ_ASSET),
			"A role grant must not reach a user with no group linking that role");
		assertEquals(0, permCount(outsider.getUuid()), "The outsider holds no effective permissions");
	}

	/**
	 * A direct user grant is scoped to its user: a different user does not inherit it.
	 */
	@Test
	public void testUserPermissionIsolation() {
		User granted = storeUser("perm_isolation_granted");
		permissionDao().grantUserPermission(granted.getUuid(), Permission.CREATE_ASSET, "iso-res");

		User other = storeUser("perm_isolation_other");

		assertEquals(0, permCount(other.getUuid()), "Another user must not see the direct grant");
		assertTrue(hasPermission(granted.getUuid(), Permission.CREATE_ASSET, "iso-res"),
			"The grantee still holds the grant");
	}

	/**
	 * The seeded admin user resolves a non-null effective permission set. Kept as a smoke test of the original assertion - the exact contents are
	 * fixture-seeded and environment-dependent, so only non-nullity is asserted here.
	 */
	@Test
	public void testLoadAdminPerms() {
		assertNotNull(permissionDao().loadPermissionsForUser(adminUser().getUuid()),
			"Loading permissions for the admin user must return a set");
	}
}
