package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;

/**
 * Coverage for the permission binding of {@link io.metaloom.loom.db.model.role.RoleDao} - the rows in {@code role_permission} which back the ACL
 * matrix in the admin area.
 *
 * <p>
 * {@code setPermissions} has <b>replace</b> semantics, not append semantics: after the call the role grants exactly the given set. That is what makes
 * the admin matrix work - unticking a box has to revoke, and a client which only ever sends the full state must not accumulate grants.
 * </p>
 */
public class RoleDaoTest extends AbstractJooqTest {

	private Role storeRole(String name) {
		Role role = roleDao().createRole(adminUser().getUuid(), name);
		roleDao().store(role);
		return role;
	}

	@Test
	public void testSetAndLoadRoundTrip() {
		Role role = storeRole("perm_roundtrip_role");

		assertTrue(roleDao().loadPermissions(role.getUuid()).isEmpty(), "A fresh role grants nothing");

		roleDao().setPermissions(role.getUuid(), Set.of(Permission.READ_ASSET, Permission.CREATE_ASSET));

		Set<Permission> loaded = roleDao().loadPermissions(role.getUuid());
		assertEquals(Set.of(Permission.READ_ASSET, Permission.CREATE_ASSET), loaded,
			"The stored permissions must load back exactly");
	}

	/**
	 * The second call replaces the first: what it omits is revoked, what it adds is granted, and what it repeats survives.
	 */
	@Test
	public void testReplaceSemantics() {
		Role role = storeRole("perm_replace_role");

		roleDao().setPermissions(role.getUuid(), Set.of(Permission.READ_ASSET, Permission.CREATE_ASSET, Permission.DELETE_ASSET));
		assertEquals(3, roleDao().loadPermissions(role.getUuid()).size());

		roleDao().setPermissions(role.getUuid(), Set.of(Permission.READ_ASSET, Permission.READ_TAG));

		Set<Permission> loaded = roleDao().loadPermissions(role.getUuid());
		assertEquals(Set.of(Permission.READ_ASSET, Permission.READ_TAG), loaded,
			"The second call must replace the set, not append to it");
		assertTrue(loaded.contains(Permission.READ_ASSET), "A permission present in both calls survives");
		assertTrue(loaded.contains(Permission.READ_TAG), "A permission new in the second call is granted");
		assertFalse(loaded.contains(Permission.CREATE_ASSET), "A permission absent from the second call is revoked");
		assertFalse(loaded.contains(Permission.DELETE_ASSET), "A permission absent from the second call is revoked");
	}

	@Test
	public void testEmptySetRevokesEverything() {
		Role role = storeRole("perm_revoke_all_role");
		roleDao().setPermissions(role.getUuid(), Set.of(Permission.READ_ASSET, Permission.READ_TAG));
		assertEquals(2, roleDao().loadPermissions(role.getUuid()).size());

		roleDao().setPermissions(role.getUuid(), Collections.emptySet());

		assertTrue(roleDao().loadPermissions(role.getUuid()).isEmpty(), "An empty set must revoke every grant");
	}

	/**
	 * Setting the same set twice must be a no-op rather than a primary key violation - the grain of {@code role_permission} is
	 * {@code (role_uuid, permission)}.
	 */
	@Test
	public void testSetIsIdempotent() {
		Role role = storeRole("perm_idempotent_role");
		Set<Permission> perms = Set.of(Permission.READ_ASSET, Permission.UPDATE_ASSET);

		roleDao().setPermissions(role.getUuid(), perms);
		roleDao().setPermissions(role.getUuid(), perms);

		assertEquals(perms, roleDao().loadPermissions(role.getUuid()), "Re-applying the same set must change nothing");
	}

	/**
	 * A null set is a programming error, not "revoke all" - the endpoint layer maps an absent request field to "leave unchanged" and must not reach
	 * the DAO at all in that case.
	 */
	@Test
	public void testNullSetIsRejected() {
		Role role = storeRole("perm_null_role");
		assertThrows(NullPointerException.class, () -> roleDao().setPermissions(role.getUuid(), null));
	}

	/**
	 * Grants belong to their role: editing one role's set leaves every other role untouched.
	 */
	@Test
	public void testGrantsAreScopedToTheirRole() {
		Role role = storeRole("perm_scoped_role");
		Role untouched = storeRole("perm_untouched_role");

		roleDao().setPermissions(role.getUuid(), Set.of(Permission.READ_ASSET));
		roleDao().setPermissions(untouched.getUuid(), Set.of(Permission.READ_ASSET, Permission.READ_TAG));

		roleDao().setPermissions(role.getUuid(), Collections.emptySet());

		assertTrue(roleDao().loadPermissions(role.getUuid()).isEmpty());
		assertEquals(Set.of(Permission.READ_ASSET, Permission.READ_TAG), roleDao().loadPermissions(untouched.getUuid()),
			"The other role's grants must survive untouched");
	}

	/**
	 * Deleting the role cascades its grants away, and only its own - the second role keeps an identical set.
	 */
	@Test
	public void testDeletingTheRoleCascadesItsGrants() {
		Role role = storeRole("perm_cascade_role");
		Role survivor = storeRole("perm_cascade_survivor_role");
		UUID roleUuid = role.getUuid();

		roleDao().setPermissions(roleUuid, Set.of(Permission.READ_ASSET, Permission.READ_TAG));
		roleDao().setPermissions(survivor.getUuid(), Set.of(Permission.READ_ASSET, Permission.READ_TAG));

		roleDao().delete(roleUuid);

		assertNull(roleDao().load(roleUuid), "The role row is gone");
		assertTrue(roleDao().loadPermissions(roleUuid).isEmpty(), "The grants must have cascaded with the role");
		assertEquals(Set.of(Permission.READ_ASSET, Permission.READ_TAG), roleDao().loadPermissions(survivor.getUuid()),
			"The untouched role must keep its identical grant set");
	}
}
