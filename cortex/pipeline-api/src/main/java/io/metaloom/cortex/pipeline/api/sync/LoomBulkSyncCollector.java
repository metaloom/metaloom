package io.metaloom.cortex.pipeline.api.sync;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;

/**
 * Collects node results that are marked for Loom synchronization and flushes them
 * in bulk using the Loom bulk API.
 *
 * <p>The collector accumulates per-media results from nodes with {@code syncToLoom() == true}
 * and writes them to Loom either when a configurable batch size is reached or when
 * {@link #flush()} is called explicitly (e.g. at the end of a pipeline run).</p>
 *
 * <p>Implementations can batch across multiple media items to reduce the number of
 * HTTP round-trips to the Loom server.</p>
 */
public interface LoomBulkSyncCollector {

	/**
	 * Collect a single node result for later synchronization.
	 *
	 * @param media    the media item
	 * @param nodeId   the node that produced the result
	 * @param result   the node result (contains output data)
	 */
	void collect(LoomMedia media, String nodeId, NodeResult result);

	/**
	 * Flush all pending results to Loom via the bulk API.
	 * Called at the end of a pipeline batch or when the internal buffer reaches capacity.
	 *
	 * @return the number of items flushed
	 */
	int flush();

	/**
	 * Return the number of pending (unflushed) items.
	 */
	int pending();
}
