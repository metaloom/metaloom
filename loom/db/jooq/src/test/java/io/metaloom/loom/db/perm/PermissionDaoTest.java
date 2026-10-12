package io.metaloom.loom.db.perm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.ResourcePermission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.user.User;

/**
 * Grant/load behaviour of {@link io.metaloom.loom.db.model.perm.PermissionDao} across the three bindings: direct user grants
 * ({@code user_permission}), role grants ({@code role_permission}) that reach a user through group membership, and token grants
 * ({@code token_permission}).
 *
 * <p>
 * Everything is asserted through {@link io.metaloom.loom.db.model.perm.PermissionDao#loadPermissionsForUser(UUID)}, the only effective-permission load
 * the interface provides. It unions a user's direct grants with the role grants reachable via {@code user_group -> role_group -> role_permission}, so a
 * permission appears exactly when the grant and (for roles) the join chain are in place.
 * </p>
 *
 * <p>
 * Scope note: permission revocation is not covered here - the DAO has no revoke API. Grant removal via hard-delete cascade is covered separately by
 * {@link io.metaloom.loom.db.jooq.dao.AclCascadeTest}.
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
	 * {@code ResourcePermission} has no {@code equals}/{@code hashCode}, so the effective set is probed by iterating. By permission alone: no grant
	 * table has a {@code resource} column any more (V2.64, V2.118), and no authorization decision ever read it.
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
	 * A direct user grant surfaces on the user's effective set - and only that grant.
	 */
	@Test
	public void testGrantUserPermission() {
		User user = storeUser("perm_user_grant");

		permissionDao().grantUserPermission(user.getUuid(), Permission.CREATE_ASSET);

		assertEquals(1, permCount(user.getUuid()), "The fresh user must hold exactly the one direct grant");
		assertTrue(hasPermission(user.getUuid(), Permission.CREATE_ASSET), "The granted permission must be loaded back");
		assertFalse(hasPermission(user.getUuid(), Permission.READ_ASSET), "A permission that was never granted must not appear");
	}

	/**
	 * A user may hold several direct grants. Before V2.118 {@code user_permission} was keyed on {@code user_uuid} alone, so the second grant here was
	 * a primary key violation.
	 */
	@Test
	public void testUserMayHoldSeveralDirectGrants() {
		User user = storeUser("perm_user_several");

		permissionDao().grantUserPermission(user.getUuid(), Permission.CREATE_ASSET);
		permissionDao().grantUserPermission(user.getUuid(), Permission.READ_ASSET);

		assertEquals(2, permCount(user.getUuid()));
		assertTrue(hasPermission(user.getUuid(), Permission.CREATE_ASSET));
		assertTrue(hasPermission(user.getUuid(), Permission.READ_ASSET));
	}

	/**
	 * Re-granting a direct permission the user already holds is a no-op, not a primary key violation.
	 */
	@Test
	public void testGrantUserPermissionIsIdempotent() {
		User user = storeUser("perm_user_idempotent");

		permissionDao().grantUserPermission(user.getUuid(), Permission.READ_ASSET);
		permissionDao().grantUserPermission(user.getUuid(), Permission.READ_ASSET);

		assertEquals(1, permCount(user.getUuid()), "The second grant must not have added a second row");
	}

	/**
	 * A token may hold several grants, re-granting is a no-op, and the grants stay with their token.
	 */
	@Test
	public void testTokenGrants() {
		Token token = tokenDao().createToken(adminUser().getUuid(), "perm_token", "perm-token-secret");
		tokenDao().store(token);
		Token other = tokenDao().createToken(adminUser().getUuid(), "perm_token_other", "perm-token-other-secret");
		tokenDao().store(other);

		permissionDao().grantTokenPermission(token.getUuid(), Permission.READ_ASSET);
		permissionDao().grantTokenPermission(token.getUuid(), Permission.CREATE_ASSET);
		permissionDao().grantTokenPermission(token.getUuid(), Permission.CREATE_ASSET);

		Set<String> granted = permissionDao().loadPermissionsForToken(token.getUuid()).stream()
			.map(ResourcePermission::getPermission)
			.collect(Collectors.toSet());
		assertEquals(Set.of(Permission.READ_ASSET.name(), Permission.CREATE_ASSET.name()), granted);
		assertEquals(2, permissionDao().loadPermissionsForToken(token.getUuid()).size(), "The re-grant must not have added a row");
		assertTrue(permissionDao().loadPermissionsForToken(other.getUuid()).isEmpty(), "Another token must not see the grants");
		assertTrue(permissionDao().loadPermissionsForToken(UUID.randomUUID()).isEmpty(), "An unknown token holds nothing");
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
		permissionDao().grantUserPermission(granted.getUuid(), Permission.CREATE_ASSET);

		User other = storeUser("perm_isolation_other");

		assertEquals(0, permCount(other.getUuid()), "Another user must not see the direct grant");
		assertTrue(hasPermission(granted.getUuid(), Permission.CREATE_ASSET),
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
