package io.metaloom.loom.pipeline.model;

/**
 * Terminal and non-terminal states a node can hold for a single media item.
 *
 * <p>Mirrors {@code io.metaloom.cortex.api.node.ResultState} by name so that the
 * two can be mapped with {@code valueOf(name())} at the wire boundary. Adding a
 * value on one side without the other breaks that mapping at runtime rather than
 * at compile time - change both together.</p>
 */
public enum NodeState {

	/** Not yet dispatched. */
	PENDING,

	/** Dispatched to a worker, result not yet received. */
	RUNNING,

	/** Finished successfully. */
	COMPLETED,

	/** Finished unsuccessfully. */
	FAILED,

	/** Not executed - dry run, failed blocking dependency, or filter branch mismatch. */
	SKIPPED;

	/**
	 * Whether no further state change is expected.
	 *
	 * @return true for COMPLETED, FAILED and SKIPPED
	 */
	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED || this == SKIPPED;
	}
}
