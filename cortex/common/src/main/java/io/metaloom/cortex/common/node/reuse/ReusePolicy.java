package io.metaloom.cortex.common.node.reuse;

/**
 * Whether, and how widely, a node may replay an output Loom already holds instead of computing.
 */
public enum ReusePolicy {

	/**
	 * Reuse an output any instance of this kind stored for the asset with the same options and inputs, whichever pipeline it ran in. The default: a
	 * transcript does not depend on which graph asked for it.
	 */
	SHARED,

	/**
	 * Reuse only what this graph-local node id stored. For a node whose persisted results are namespaced by its node id, so that skipping in another
	 * pipeline would leave that pipeline's own namespace empty.
	 */
	PER_NODE_ID,

	/**
	 * Always compute. For a node whose work is a side effect (moving files, uploading, assigning) or whose answer depends on Loom state that is not
	 * among its inputs (ratings, tags, reviewer decisions, the dedup index).
	 */
	NEVER

}
