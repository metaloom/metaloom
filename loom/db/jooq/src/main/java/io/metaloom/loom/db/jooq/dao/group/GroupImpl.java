package io.metaloom.loom.db.jooq.dao.group;

import java.util.List;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.user.User;

public class GroupImpl extends AbstractEditableElement<Group> implements Group {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Group setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public List<User> getUsers() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Group setUsers(List<User> users) {
		// TODO Auto-generated method stub
		return null;
	}

}
