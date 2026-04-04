package io.metaloom.cortex.pipeline.api.node;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;
import io.metaloom.cortex.api.media.LoomMedia;

/**
 * A node within a processing pipeline. Nodes execute actions on media items.
 * Each node can declare dependencies on other nodes and configure its execution mode.
 */
public interface PipelineNode {

	/**
	 * Unique identifier for this node within the pipeline (e.g. "sha512", "thumbnail", "loom-sync").
	 */
	String id();

	/**
	 * Human-readable name.
	 */
	String name();

	/**
	 * Whether this node runs in parallel or sequentially.
	 */
	NodeMode mode();

	/**
	 * Whether this node blocks the pipeline — a blocking node must complete before dependent nodes start.
	 * Non-blocking nodes emit results asynchronously via the event bus.
	 */
	boolean isBlocking();

	/**
	 * IDs of nodes that must complete before this node can execute.
	 */
	Set<String> dependencies();

	/**
	 * The maximum number of concurrent workers for this node (job queue size).
	 * Controls per-node scaling (e.g. hasher=4, whisper=1, llm=4).
	 */
	int concurrency();

	/**
	 * Process a single media item. Returns the result of the processing.
	 *
	 * @param media the media item to process
	 * @param upstreamResults results from completed upstream (dependency) nodes, keyed by node id
	 * @return the processing result
	 */
	NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults);

	/**
	 * Optional node-specific configuration parameters.
	 */
	default Map<String, Object> options() {
		return Collections.emptyMap();
	}

	/**
	 * Optional cache provider for this node. When set, results are cached and looked up
	 * before invoking {@link #process(LoomMedia, Map)}.
	 */
	default NodeCacheProvider cacheProvider() {
		return null;
	}

	/**
	 * Initialize the node (called once before the pipeline starts processing).
	 */
	default void initialize() {
	}

	/**
	 * Shutdown the node (called once after the pipeline finishes processing).
	 */
	default void shutdown() {
	}
}
