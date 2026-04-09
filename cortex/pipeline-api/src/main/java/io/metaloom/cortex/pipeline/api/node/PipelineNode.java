package io.metaloom.cortex.pipeline.api.node;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.api.media.LoomMedia;

/**
 * A node within a processing pipeline. Nodes execute actions on media items.
 * Each node can declare dependencies on other nodes and configure its execution mode.
 *
 * <p>Filter nodes emit a {@value #FILTER_PASSED} boolean output to signal pass/reject.
 * Downstream nodes can use {@link #conditionalDependencies()} to bind to a specific branch.</p>
 */
public interface PipelineNode {

	/**
	 * Standard output key emitted by filter nodes. {@code true} means the media passed
	 * the filter condition; {@code false} means it was rejected.
	 */
	String FILTER_PASSED = "filter_passed";

	/**
	 * Unique identifier for this node within the pipeline (e.g. "sha512", "thumbnail", "loom-sync").
	 */
	String id();

	/**
	 * Human-readable name.
	 */
	String name();

	/**
	 * Whether this node is a source node that yields media assets.
	 * A pipeline must have exactly one source node.
	 */
	default boolean isSource() {
		return false;
	}

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
	 * Conditional dependency declarations for filter-based branching. Maps a dependency
	 * node id to the {@link FilterBranch} this node expects. If a dependency emits a
	 * {@value #FILTER_PASSED} output that does not match the declared branch, this node
	 * is skipped.
	 *
	 * <p>Only entries for filter nodes need to be declared; regular dependencies default
	 * to {@link FilterBranch#ANY}.</p>
	 *
	 * @return map of dependency node id → required filter branch (empty by default)
	 */
	default Map<String, FilterBranch> conditionalDependencies() {
		return Collections.emptyMap();
	}

	/**
	 * The maximum number of concurrent workers for this node (job queue size).
	 * Controls per-node scaling (e.g. hasher=4, whisper=1, llm=4).
	 */
	int concurrency();

	/**
	 * Whether the result of this node should be synchronized to the Loom backend.
	 * Nodes that produce metadata (hashes, descriptions, transcripts, fingerprints)
	 * typically return {@code true}. The pipeline executor collects results from all
	 * sync-eligible nodes and flushes them in bulk via {@link io.metaloom.cortex.pipeline.api.sync.LoomBulkSyncCollector}.
	 */
	default boolean syncToLoom() {
		return false;
	}

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
