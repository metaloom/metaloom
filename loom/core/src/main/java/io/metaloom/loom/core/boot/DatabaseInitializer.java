package io.metaloom.loom.core.boot;

import java.time.Instant;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.common.options.LoomEnv;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.utils.StringUtils;
import io.metaloom.utils.UUIDUtils;

@Singleton
public class DatabaseInitializer {

	public static final String GROUP_NAME = "admins";
	public static final String ROLE_NAME = "admin-role";

	private final LoomOptions options;
	private final UserDao userDao;
	private final GroupDao groupDao;
	private final RoleDao roleDao;
	private final PermissionDao permissionDao;
	private final AuthenticationService authService;

	private User adminUser;

	@Inject
	public DatabaseInitializer(LoomOptions options, UserDao userDao, GroupDao groupDao, RoleDao roleDao, PermissionDao permissionDao,
		AuthenticationService authService) {
		this.options = options;
		this.userDao = userDao;
		this.groupDao = groupDao;
		this.roleDao = roleDao;
		this.permissionDao = permissionDao;
		this.authService = authService;
	}

	public void init() {

		// User
		User adminUser = userDao.loadAdmin();
		if (adminUser == null) {
			adminUser = userDao.createUser(UserDao.ADMIN_USER_NAME);
			adminUser.setUuid(UUIDUtils.randomUUID());
			adminUser.setCreator(adminUser);
			adminUser.setEditor(adminUser);
			adminUser.setEdited(Instant.now());
			adminUser.setCreated(Instant.now());

			String password = LoomEnv.initialPassword();
			if (password == null) {
				password = StringUtils.randomHumanString(8);
				System.out.println("####################");
				System.out.println("# Initial Password #");
				System.out.println("####################");
				System.out.println("# USER: " + UserDao.ADMIN_USER_NAME);
				System.out.println("# PASS: " + password);
				System.out.println("####################");
			}
			adminUser.setPasswordHash(authService.encodePassword(password));
			userDao.store(adminUser);
		}

		// Group + Assign User to Group
		Group group = groupDao.loadByName(GROUP_NAME);
		if (group == null) {
			group = groupDao.create(adminUser, GROUP_NAME);
			group.setUuid(UUIDUtils.randomUUID());
			groupDao.store(group);
		}
		groupDao.addUserToGroup(group, adminUser);

		// Role + Assign Role to Group + Role Permission
		Role role = roleDao.loadByName(ROLE_NAME);
		if (role == null) {
			role = roleDao.createRole(adminUser.getUuid(), ROLE_NAME);
			role.setUuid(UUIDUtils.randomUUID());
			roleDao.store(role);
			// Grand all perms to the role
			for (Permission perm : Permission.values()) {
				permissionDao.grantRolePermission(role.getUuid(), perm);
			}
		}
		groupDao.addRoleToGroup(group, role);

	}

}
