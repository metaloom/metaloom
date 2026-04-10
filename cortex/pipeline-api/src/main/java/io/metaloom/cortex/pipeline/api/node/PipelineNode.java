package io.metaloom.cortex.pipeline.api.node;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.pipeline.api.MediaContext;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.PartitionedFlowable;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.api.media.LoomMedia;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * A node within a processing pipeline. Nodes execute actions on media items.
 * Nodes are connected into a DAG via {@link #connectTo(PipelineNode)} and
 * {@link #connectTo(PipelineNode, FilterBranch)}.
 *
 * <p>Filter nodes emit a {@value #FILTER_PASSED} boolean output to signal pass/reject.
 * Downstream nodes connected with a specific {@link FilterBranch} are skipped when
 * the branch does not match.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * sourceNode.connectTo(sizeFilter);
 * sizeFilter.connectTo(hashNode, FilterBranch.PASS);
 * sizeFilter.connectTo(tikaNode, FilterBranch.PASS);
 * 
 * Pipeline pipeline = DefaultPipeline.builder("my-pipeline")
 *     .source(sourceNode)
 *     .build();
 * </pre>
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
	 * Computed from the inverse of the connection graph (parents of this node).
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
	 * Connect this node to a downstream node. The downstream node will depend on this node
	 * and will only execute after this node completes.
	 *
	 * @param downstream the downstream node to connect to
	 * @return this node for fluent chaining
	 */
	PipelineNode connectTo(PipelineNode downstream);

	/**
	 * Connect this node to a downstream node with a filter branch condition.
	 * The downstream node will only execute if this node's filter output matches the branch.
	 *
	 * @param downstream the downstream node to connect to
	 * @param branch     the filter branch (PASS or REJECT) the downstream node requires
	 * @return this node for fluent chaining
	 */
	PipelineNode connectTo(PipelineNode downstream, FilterBranch branch);

	/**
	 * Return the list of downstream nodes connected via {@link #connectTo}.
	 *
	 * @return unmodifiable list of downstream children
	 */
	List<PipelineNode> children();

	/**
	 * Process a single media item. Returns the result of the processing.
	 *
	 * @param media the media item to process
	 * @param upstreamResults results from completed upstream (dependency) nodes, keyed by node id
	 * @return the processing result
	 */
	NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults);

	/**
	 * Apply this node as a reactive operator on the input stream. The default
	 * implementation wraps {@link #process} in a {@code flatMap} with
	 * backpressure-aware concurrency control.
	 *
	 * <p>Override to customise scheduling, error handling, or to implement
	 * streaming operators that cannot be expressed as single-item transformations.</p>
	 *
	 * @param input the upstream media context flowable
	 * @return a flowable of media contexts enriched with this node's result
	 */
	default Flowable<MediaContext> apply(Flowable<MediaContext> input) {
		return input.flatMap(ctx ->
			Flowable.fromCallable(() -> {
				NodeResult result = process(ctx.getMedia(), ctx.getUpstreamResults());
				return ctx.withResult(id(), result);
			}).subscribeOn(Schedulers.io()),
			concurrency()
		);
	}

	/**
	 * Whether this node supports partitioning its output into two branches
	 * (PASS / REJECT). Filter nodes typically return {@code true}.
	 */
	default boolean isPartitioning() {
		return false;
	}

	/**
	 * Partition the input stream into PASS and REJECT branches based on this
	 * node's filter evaluation. Only meaningful when {@link #isPartitioning()}
	 * returns {@code true}.
	 *
	 * @param input the upstream media context flowable
	 * @return a {@link PartitionedFlowable} with pass and reject branches
	 * @throws UnsupportedOperationException if this node does not support partitioning
	 */
	default PartitionedFlowable<MediaContext> partition(Flowable<MediaContext> input) {
		throw new UnsupportedOperationException(id() + " does not support partitioning");
	}

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
