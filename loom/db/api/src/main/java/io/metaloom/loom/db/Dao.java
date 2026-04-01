package io.metaloom.loom.db;

public interface Dao {

	/**
	 * Type name of the DAO which helps to identify it.
	 * 
	 * @return
	 */
	String getTypeName();

	/**
	 * Clear the elements that are managed by the DAO.
	 */
	void clear();

	/**
	 * Count all elements that are managed by the DAO.
	 * 
	 * @return
	 */
	long count();
	

}
