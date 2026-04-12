package io.metaloom.cortex;

import io.metaloom.cortex.api.option.CortexOptions;

/**
 * Factory used by the {@link Cortex#create(CortexOptions)} static method.
 * The implementation is provided by the core module.
 */
public interface CortexFactory {

	static Cortex create(CortexOptions options) {
		// Delegate to impl via ServiceLoader-style - for now direct instantiation
		// will be handled by the container runner directly
		throw new UnsupportedOperationException("Use the container runner to create Cortex instances");
	}

}
