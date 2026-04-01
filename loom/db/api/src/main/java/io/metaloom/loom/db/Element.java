package io.metaloom.loom.db;

import java.util.UUID;

public interface Element<SELF extends Element<SELF>> {

//	PT getId();
//
//	SELF setId(PT id);

	/**
	 * Return the UUID of the element.
	 * 
	 * @return
	 */
	UUID getUuid();

	/**
	 * Set the UUID of the element.
	 * 
	 * @param uuid
	 * @return Fluent API
	 */
	SELF setUuid(UUID uuid);

	/**
	 * @return a reference to this container instance, cast to the expected generic type.
	 */
	@SuppressWarnings("unchecked")
	default SELF self() {
		return (SELF) this;
	}
}
