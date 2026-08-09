package io.metaloom.cortex.node.relocate;

/**
 * Where a {@code move} node sends an asset's bytes.
 *
 * <p>
 * This is the seam that keeps one node kind serving four destinations, exactly as {@code FilterBy} does for the filter node: each constant is backed
 * by a {@link MoveDestination}, and adding one is a strategy class plus a Dagger binding, never an edit to {@link MoveNode}.
 * </p>
 *
 * <p>
 * Note what is <b>not</b> here. A collection is a logical grouping with no path and no bytes, so "move to a collection" is not a move at all - it is
 * the {@code assign} node. Keeping the two apart is why a node that relocates 40 GB never quietly turns into one that writes a join row.
 * </p>
 */
public enum MoveTarget {

	/**
	 * A folder on the worker's own filesystem. The only target that needs nothing from Loom, and therefore the only one that works offline.
	 */
	FOLDER,

	/**
	 * A storage pool ({@code asset_pool}), which is either a filesystem root or an S3 bucket - the row carries exactly one of the two.
	 *
	 * <p>
	 * ⚠️ A filesystem pool's {@code fs_path} is a path on the <b>Loom server</b>. The node writes from the worker, so the pool root has to be visible
	 * there too; {@link PoolDestination} checks that and fails loudly rather than writing to a worker-local path that means nothing to Loom.
	 * </p>
	 */
	POOL,

	/**
	 * A library, which resolves to the pool the library points at, and additionally re-points the binary's {@code library_uuid}.
	 *
	 * <p>
	 * A library has no filesystem root of its own - only a pool - so this is POOL plus a membership fact.
	 * </p>
	 */
	LIBRARY,

	/**
	 * An S3 bucket named directly on the node, which need not correspond to any pool row. The cold-tier case, and the one target that works when the
	 * worker shares no filesystem with anything.
	 */
	S3_BUCKET
}
