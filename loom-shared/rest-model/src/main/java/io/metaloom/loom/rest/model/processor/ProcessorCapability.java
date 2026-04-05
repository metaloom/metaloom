package io.metaloom.loom.rest.model.processor;

/**
 * Capabilities that a processor node can provide.
 * Loom uses these to select appropriate nodes for different workloads.
 */
public enum ProcessorCapability {

	/**
	 * I/O intensive operations (e.g. filesystem scans, network transfers).
	 */
	IO,

	/**
	 * CPU intensive operations (e.g. fingerprinting, hashing).
	 */
	CPU,

	/**
	 * GPU accelerated operations (e.g. face detection, embedding generation).
	 */
	GPU
}
