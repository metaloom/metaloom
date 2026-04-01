package io.metaloom.loom.db.model.group;

import java.util.List;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.model.user.User;

public interface Group extends CUDElement<Group> {

	/**
	 * Return the name of the group.
	 * 
	 * @return
	 */
	String getName();

	/**
	 * Set the name of the group.
	 * 
	 * @param name
	 * @return
	 */
	Group setName(String name);

	List<User> getUsers();

	Group setUsers(List<User> users);

}
