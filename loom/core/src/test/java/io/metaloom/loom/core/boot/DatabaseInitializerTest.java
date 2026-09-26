package io.metaloom.loom.core.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.user.User;

/**
 * {@link DatabaseInitializer} bootstraps the admin user, the built-in admin group/role and grants every {@link Permission} to that role. The extension
 * already runs it once while booting the component for every test method, which is what makes the interesting case here cheap to reach: calling
 * {@code init()} again against the same, already-seeded database.
 *
 * <p>
 * That second call used to only re-link the group and the role - re-granting {@code Permission.values()} onto the admin role happened solely inside
 * the "role did not exist yet" branch. On a long-lived installation the role always exists after the first boot, so a later release that adds a new
 * {@link Permission} constant would never grant it to the built-in admin role: every boot after the upgrade would recreate the same role, skip the
 * grant loop because the role was found rather than created, and admin would 403 on whatever endpoint the new permission guards - silently, with no
 * migration and no log line pointing at the cause.
 * </p>
 */
public class DatabaseInitializerTest {

	@RegisterExtension
	public static LoomCoreTestExtension ext = new LoomCoreTestExtension();

	@Test
	public void shouldGrantEveryPermissionToTheAdminRoleEvenWhenTheRoleAlreadyExists() {
		DaoCollection daos = ext.internal().daos();
		AuthenticationService authService = ext.internal().authService();

		RoleDao roleDao = daos.roleDao();
		PermissionDao permissionDao = daos.permissionDao();

		// The extension's own beforeEach() already booted once, so the admin role exists and (at the
		// time this test was written) already carries every permission. Revoke one to simulate the real
		// scenario: an existing installation whose admin role predates a later Permission constant.
		Role adminRole = roleDao.loadByName(DatabaseInitializer.ROLE_NAME);
		assertNotNull(adminRole, "the extension's own boot must have created the admin role already");
		Set<Permission> before = roleDao.loadPermissions(adminRole.getUuid());
		assertTrue(before.contains(Permission.READ_ASSET), "sanity: the role starts out fully granted");

		roleDao.setPermissions(adminRole.getUuid(), Set.of());
		assertTrue(roleDao.loadPermissions(adminRole.getUuid()).isEmpty(), "the permission was actually revoked");

		// Re-running the initializer against the same database - as happens on every ordinary restart -
		// must restore every permission on the pre-existing role, not just on a freshly created one.
		DatabaseInitializer initializer = new DatabaseInitializer(new LoomOptions(), daos.userDao(), daos.groupDao(), roleDao, permissionDao,
			authService);
		initializer.init();

		Set<Permission> after = roleDao.loadPermissions(adminRole.getUuid());
		for (Permission perm : Permission.values()) {
			assertTrue(after.contains(perm), "re-running the initializer must (re-)grant " + perm + " to the pre-existing admin role");
		}
		assertEquals(Permission.values().length, after.size());
	}

	@Test
	public void shouldStayIdempotentAcrossRepeatedBoots() {
		DaoCollection daos = ext.internal().daos();
		AuthenticationService authService = ext.internal().authService();

		User adminBefore = daos.userDao().loadAdmin();
		Group groupBefore = daos.groupDao().loadByName(DatabaseInitializer.GROUP_NAME);
		Role roleBefore = daos.roleDao().loadByName(DatabaseInitializer.ROLE_NAME);

		DatabaseInitializer initializer = new DatabaseInitializer(new LoomOptions(), daos.userDao(), daos.groupDao(), daos.roleDao(),
			daos.permissionDao(), authService);
		// Must not throw: re-creating the admin, the group or the role, or re-granting permissions and
		// re-linking memberships that already exist, are all expected to be no-ops rather than
		// constraint violations.
		initializer.init();
		initializer.init();

		User adminAfter = daos.userDao().loadAdmin();
		Group groupAfter = daos.groupDao().loadByName(DatabaseInitializer.GROUP_NAME);
		Role roleAfter = daos.roleDao().loadByName(DatabaseInitializer.ROLE_NAME);

		assertEquals(adminBefore.getUuid(), adminAfter.getUuid(), "the admin user must not be recreated on a later boot");
		assertEquals(groupBefore.getUuid(), groupAfter.getUuid(), "the admin group must not be recreated on a later boot");
		assertEquals(roleBefore.getUuid(), roleAfter.getUuid(), "the admin role must not be recreated on a later boot");
	}
}
