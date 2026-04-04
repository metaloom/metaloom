package io.metaloom.cortex.pipeline.api;

/**
 * Execution mode for a pipeline node.
 */
public enum NodeMode {

	/**
	 * Node runs in sequence — waits for completion before the next node starts.
	 */
	SEQUENTIAL,

	/**
	 * Node can run in parallel with other parallel nodes in the same group.
	 */
	PARALLEL
}
