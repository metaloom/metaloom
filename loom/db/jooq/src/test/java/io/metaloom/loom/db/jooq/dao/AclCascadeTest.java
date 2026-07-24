package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Delete-cascade behaviour of the ACL join and grant tables defined in {@code V2.1__add_acl.sql}.
 *
 * <p>
 * These cascades are what keep a role or group deletion from stranding membership and grant rows: {@code role_permission} and {@code user_permission}
 * cascade from their principal, and {@code role_group} / {@code user_group} cascade from both sides. A lost cascade would surface either as an orphan
 * row or as a foreign-key violation on delete.
 * </p>
 *
 * <p>
 * The tests probe the cascades through {@link io.metaloom.loom.db.model.perm.PermissionDao#loadPermissionsForUser(UUID)}: a permission that is only
 * reachable through the row under test disappears from the user's effective set exactly when that row is removed.
 * </p>
 */
public class AclCascadeTest extends AbstractJooqTest {

	private User creator() {
		return adminUser();
	}

	private boolean hasPermission(UUID userUuid, Permission perm, String resource) {
		for (ResourcePermission rp : permissionDao().loadPermissionsForUser(userUuid)) {
			if (perm.name().equals(rp.getPermission()) && resource.equals(rp.getResource())) {
				return true;
			}
		}
		return false;
	}

	private Role storeRole(String name) {
		Role role = roleDao().createRole(creator().getUuid(), name);
		roleDao().store(role);
		return role;
	}

	private Group storeGroup(String name) {
		Group group = groupDao().create(creator(), name);
		groupDao().store(group);
		return group;
	}

	private User storeUser(String username) {
		User user = userDao().createUser(username);
		userDao().store(user);
		return user;
	}

	/**
	 * Deleting a role cascades {@code role_permission} (its grants) and {@code role_group} (its group links); the group itself survives.
	 */
	@Test
	public void testDeletingARoleCascadesGrantsAndGroupLinks() {
		String resource = "res-role";
		Role role = storeRole("cascade_role");
		Group group = storeGroup("group_holding_cascade_role");
		groupDao().addRoleToGroup(group, role);
		// grantRolePermission uses the "all" resource by default; use the explicit form so the
		// probe can match on a known (permission, resource) pair.
		permissionDao().grantRolePermission(role.getUuid(), Permission.READ_ASSET, resource);

		// A member of the group reaches the permission only through this role.
		User member = storeUser("role_cascade_member");
		groupDao().addUserToGroup(group, member);

		assertTrue(hasPermission(member.getUuid(), Permission.READ_ASSET, resource),
			"The member should reach the role's permission before the role is deleted");
		assertEquals(1, groupDao().loadRoles(group).size(), "The role is linked to the group");

		roleDao().delete(role.getUuid());

		assertNotNull(groupDao().load(group.getUuid()), "The group must survive deletion of its role");
		assertEquals(0, groupDao().loadRoles(group).size(), "The role_group link must have cascaded");
		assertFalse(hasPermission(member.getUuid(), Permission.READ_ASSET, resource),
			"The role_permission grant must have cascaded, so the member no longer reaches it");
	}

	/**
	 * Deleting a group cascades {@code user_group} (memberships) and {@code role_group} (role links); the member and the role survive.
	 */
	@Test
	public void testDeletingAGroupCascadesMembershipsAndRoleLinks() {
		String resource = "res-group";
		Role role = storeRole("role_for_cascade_group");
		permissionDao().grantRolePermission(role.getUuid(), Permission.READ_ASSET, resource);
		User member = storeUser("group_cascade_member");
		Group group = storeGroup("cascade_group");
		groupDao().addUserToGroup(group, member);
		groupDao().addRoleToGroup(group, role);

		assertTrue(hasPermission(member.getUuid(), Permission.READ_ASSET, resource),
			"The member should reach the role's permission through the group before deletion");

		// A missing cascade would make this DELETE fail with a foreign-key violation.
		groupDao().delete(group.getUuid());

		assertNull(groupDao().load(group.getUuid()), "The group is gone");
		assertNotNull(userDao().load(member.getUuid()), "The member must survive deletion of the group");
		assertNotNull(roleDao().load(role.getUuid()), "The role must survive deletion of the group");
		assertFalse(hasPermission(member.getUuid(), Permission.READ_ASSET, resource),
			"The user_group membership must have cascaded, so the member no longer reaches the role's permission");
	}

	/**
	 * Soft delete ({@link User#markDeleted()} + update) keeps the row and its grant/membership rows; the user is only filtered out of load.
	 *
	 * <p>
	 * NB: this is <b>not</b> {@code userDao().delete(...)} - that is a hard delete (see the next test). The soft-delete path is what the REST layer
	 * uses, and it deliberately leaves grants intact.
	 * </p>
	 */
	@Test
	public void testSoftDeletingAUserKeepsGrantsAndMemberships() {
		String resource = "res-soft";
		User user = storeUser("soft_deleted_user");
		permissionDao().grantUserPermission(user.getUuid(), Permission.READ_ASSET, resource);
		Group group = storeGroup("group_of_soft_user");
		groupDao().addUserToGroup(group, user);

		user.markDeleted();
		userDao().update(user);

		assertNull(userDao().load(user.getUuid()), "A soft-deleted user is filtered out of load()");
		assertTrue(hasPermission(user.getUuid(), Permission.READ_ASSET, resource),
			"Soft delete does not physically remove the row, so its direct grant survives - cascades only fire on a hard delete");
	}

	/**
	 * Hard delete ({@code userDao().delete(...)}) removes the row and cascades {@code user_permission} and {@code user_group}; the group survives.
	 */
	@Test
	public void testHardDeletingAUserCascadesGrantsAndMemberships() {
		String resource = "res-hard";
		User user = storeUser("hard_deleted_user");
		permissionDao().grantUserPermission(user.getUuid(), Permission.READ_ASSET, resource);
		Group group = storeGroup("group_of_hard_user");
		groupDao().addUserToGroup(group, user);

		assertTrue(hasPermission(user.getUuid(), Permission.READ_ASSET, resource),
			"The direct grant resolves before the hard delete");

		userDao().delete(user.getUuid());

		assertNull(userDao().load(user.getUuid()), "The user row is gone");
		assertFalse(hasPermission(user.getUuid(), Permission.READ_ASSET, resource),
			"The user_permission grant must have cascaded with the row");
		assertNotNull(groupDao().load(group.getUuid()), "The group must survive the hard delete of a member");
	}
}
