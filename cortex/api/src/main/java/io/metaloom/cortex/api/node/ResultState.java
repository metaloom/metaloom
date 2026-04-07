package io.metaloom.cortex.api.node;

/**
 * Result state for a node that processed a media.
 */
public enum ResultState {

	/**
	 * Processing has been skipped
	 */
	SKIPPED,

	/**
	 * Processing has failed
	 */
	FAILED,

	/**
	 * Processing was successful.
	 */
	SUCCESS;
}
