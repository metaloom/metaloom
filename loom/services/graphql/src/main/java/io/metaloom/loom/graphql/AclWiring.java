package io.metaloom.loom.graphql;

import java.util.List;
import java.util.stream.Collectors;

import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;

/**
 * Wiring for the access control domain: users, groups and roles.
 *
 * <p>The password hash of a user is intentionally not part of the schema - there is no field that could leak it, not even to an administrator.</p>
 */
public class AclWiring extends AbstractDomainWiring {

	private final UserDao userDao;
	private final GroupDao groupDao;
	private final RoleDao roleDao;

	public AclWiring(DaoCollection daos) {
		this.userDao = daos.userDao();
		this.groupDao = daos.groupDao();
		this.roleDao = daos.roleDao();
	}

	@Override
	public void wire(RuntimeWiring.Builder builder) {

		// User
		DataFetcher<User> userFetcher = env -> {
			requirePermission(env, Permission.READ_USER);
			return userDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<User> userByUsernameFetcher = env -> {
			requirePermission(env, Permission.READ_USER);
			return userDao.loadByUsername(env.getArgument("username"));
		};

		DataFetcher<List<? extends User>> usersFetcher = env -> {
			requirePermission(env, Permission.READ_USER);
			return userDao.findAll().collect(Collectors.toList());
		};

		DataFetcher<Boolean> ssoFetcher = env -> {
			// The getter is isSSO(), which the property fetcher cannot derive from the field name "sso".
			User user = env.getSource();
			return user.isSSO();
		};

		DataFetcher<List<Group>> userGroupsFetcher = env -> {
			requirePermission(env, Permission.READ_GROUP);
			User user = env.getSource();
			return orEmpty(groupDao.loadGroupsForUser(user.getUuid()));
		};

		// Group
		DataFetcher<Group> groupFetcher = env -> {
			requirePermission(env, Permission.READ_GROUP);
			return groupDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<Group> groupByNameFetcher = env -> {
			requirePermission(env, Permission.READ_GROUP);
			return groupDao.loadByName(env.getArgument("name"));
		};

		DataFetcher<List<? extends Group>> groupsFetcher = env -> {
			requirePermission(env, Permission.READ_GROUP);
			return groupDao.findAll().collect(Collectors.toList());
		};

		DataFetcher<List<User>> groupUsersFetcher = env -> {
			requirePermission(env, Permission.READ_USER);
			Group group = env.getSource();
			return orEmpty(groupDao.loadUsersForGroup(group.getUuid()));
		};

		DataFetcher<List<Role>> groupRolesFetcher = env -> {
			requirePermission(env, Permission.READ_ROLE);
			Group group = env.getSource();
			return orEmpty(groupDao.loadRoles(group));
		};

		// Role
		DataFetcher<Role> roleFetcher = env -> {
			requirePermission(env, Permission.READ_ROLE);
			return roleDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<Role> roleByNameFetcher = env -> {
			requirePermission(env, Permission.READ_ROLE);
			return roleDao.loadByName(env.getArgument("name"));
		};

		DataFetcher<List<? extends Role>> rolesFetcher = env -> {
			requirePermission(env, Permission.READ_ROLE);
			return roleDao.findAll().collect(Collectors.toList());
		};

		builder
			.type(TypeRuntimeWiring.newTypeWiring("Query")
				.dataFetcher("user", userFetcher)
				.dataFetcher("userByUsername", userByUsernameFetcher)
				.dataFetcher("users", usersFetcher)
				.dataFetcher("group", groupFetcher)
				.dataFetcher("groupByName", groupByNameFetcher)
				.dataFetcher("groups", groupsFetcher)
				.dataFetcher("role", roleFetcher)
				.dataFetcher("roleByName", roleByNameFetcher)
				.dataFetcher("roles", rolesFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("User")
				.dataFetcher("sso", ssoFetcher)
				.dataFetcher("groups", userGroupsFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("Group")
				.dataFetcher("users", groupUsersFetcher)
				.dataFetcher("roles", groupRolesFetcher));
	}

}
