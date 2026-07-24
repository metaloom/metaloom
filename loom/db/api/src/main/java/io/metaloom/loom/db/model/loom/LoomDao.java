package io.metaloom.loom.db.model.loom;

import io.metaloom.loom.db.Dao;

/**
 * DAO for the singleton {@link Loom} system row. The {@code loom} table holds a single row, so this DAO deliberately does not extend
 * {@link io.metaloom.loom.db.CRUDDao} - there is no per-element UUID to load, delete or page over.
 */
public interface LoomDao extends Dao {

	/**
	 * Load the singleton loom system row.
	 *
	 * @return the loom row, or {@code null} if it has not yet been created
	 */
	Loom load();

	/**
	 * Create and persist the singleton loom system row with the given database revision. The last-used timestamp is set to the current time.
	 *
	 * @param databaseRevision
	 * @return the created loom row
	 */
	Loom createLoom(String databaseRevision);

	/**
	 * Update the singleton loom system row with the values of the given element.
	 *
	 * @param loom
	 */
	void update(Loom loom);
}
