package io.metaloom.loom.db.jooq.dao.role;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.role.Role;

public class RoleImpl extends AbstractEditableElement<Role> implements Role {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Role setName(String name) {
		this.name = name;
		return this;
	}

}
