package io.metaloom.loom.db.model.group;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;

public interface GroupDao extends CRUDDao<Group> {

	default Group create(User user, String name) {
		return createGroup(user.getUuid(), name);
	}

	Group createGroup(UUID userUuid, String name);

	// Users
	void addUserToGroup(Group group, User user);

	void removeUserFromGroup(Group group, User user);

	User loadUsers(Group group);

	// Roles
	void addRoleToGroup(Group group, Role role);

	void removeRoleFromGroup(Group group, Role role);

	List<Role> loadRoles(Group group);

	void testMultiOp();

	/**
	 * Load the group by its name.
	 * 
	 * @param name
	 * @return
	 */
	Group loadByName(String name);

}
