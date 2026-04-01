package io.metaloom.loom.db.model.role;

import io.metaloom.loom.db.CUDElement;

public interface Role extends CUDElement<Role> {

	/**
	 * Return the name of the role.
	 * 
	 * @return
	 */
	String getName();

	/**
	 * Set the name of the role.
	 * 
	 * @param name
	 * @return Fluent API
	 */
	Role setName(String name);
}
