package io.metaloom.loom.db.model.loom;

import java.time.Instant;

/**
 * The {@code loom} table is a singleton system row that tracks the current database revision and the timestamp at which the system was last used. It
 * is not a regular CRUD entity - there is exactly one row and it carries no UUID.
 */
public interface Loom {

	/**
	 * Return the database revision that the system was last provisioned/migrated to.
	 *
	 * @return
	 */
	String getDatabaseRevision();

	/**
	 * Set the database revision.
	 *
	 * @param databaseRevision
	 * @return Fluent API
	 */
	Loom setDatabaseRevision(String databaseRevision);

	/**
	 * Return the timestamp at which the system was last used.
	 *
	 * @return
	 */
	Instant getLastUsedTimestamp();

	/**
	 * Set the timestamp at which the system was last used.
	 *
	 * @param lastUsedTimestamp
	 * @return Fluent API
	 */
	Loom setLastUsedTimestamp(Instant lastUsedTimestamp);
}
