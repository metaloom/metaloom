package io.metaloom.loom.api;

import io.metaloom.loom.api.options.LoomOptionsLookup;

public interface LoomFactory {

	/**
	 * Return a new instance of loom.
	 * 
	 * @return Loom instance
	 */
	Loom create();

	/**
	 * Return a new instance of loom.
	 * 
	 * @param optionsLookup
	 *            Loom options lookup
	 * @return Loom instance
	 */
	Loom create(LoomOptionsLookup optionsLookup);
}
