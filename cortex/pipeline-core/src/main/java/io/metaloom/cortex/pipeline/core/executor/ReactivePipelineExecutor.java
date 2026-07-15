package io.metaloom.cortex.pipeline.core.executor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineExecutor;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineEventBus;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent.Type;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.api.sync.LoomBulkSyncCollector;
import io.metaloom.cortex.pipeline.common.cache.NoOpNodeCache;
import io.metaloom.cortex.pipeline.common.event.DefaultPipelineEventBus;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Reactive pipeline executor built on RxJava 3. Media items flow through the pipeline
 * as a backpressure-aware {@link Flowable}. The internal node DAG is composed per media
 * item using {@link Single} with {@code Single.zip} for multi-parent dependency gathering.
 *
 * <h3>Backpressure</h3>
 * <p>The {@code maxConcurrentMedia} parameter controls how many media items are processed
 * in parallel via {@code Flowable.flatMap(fn, maxConcurrentMedia)}. When downstream cannot
 * keep up, the source is paused automatically.</p>
 *
 * <h3>Per-node concurrency</h3>
 * <p>Each node has a {@link Semaphore} sized to {@link PipelineNode#concurrency()}.
 * This limits how many media items can execute a given node simultaneously
 * (e.g. whisper=1, hasher=4, llm=4) across all in-flight media items.</p>
 *
 * <h3>Dependency resolution</h3>
 * <p>For each media item the executor builds a DAG of {@code Single<NodeResult>} values.
 * A node with multiple parents uses {@code Single.zip} to wait for all of them. Filter
 * branch conditions and blocking-failure checks are evaluated before node execution.</p>
 */
public class ReactivePipelineExecutor implements PipelineExecutor {

	private static final Logger log = LoggerFactory.getLogger(ReactivePipelineExecutor.class);

	/** Interval for periodic NODE_STATS emission (500 ms). */
	private static final long STATS_INTERVAL_MS = 500;

	private final int maxConcurrentMedia;
	private final PipelineEventBus eventBus;
	private final LoomBulkSyncCollector syncCollector;
	private final Map<String, Semaphore> nodeSemaphores = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> nodeProcessedCounts = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> nodeFailedCounts = new ConcurrentHashMap<>();
	private final ScheduledExecutorService statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "pipeline-stats-emitter");
		t.setDaemon(true);
		return t;
	});

	public ReactivePipelineExecutor(int maxConcurrentMedia) {
		this(maxConcurrentMedia, new DefaultPipelineEventBus(), null);
	}

	public ReactivePipelineExecutor(int maxConcurrentMedia, PipelineEventBus eventBus) {
		this(maxConcurrentMedia, eventBus, null);
	}

	public ReactivePipelineExecutor(int maxConcurrentMedia, PipelineEventBus eventBus,
			LoomBulkSyncCollector syncCollector) {
		this.maxConcurrentMedia = maxConcurrentMedia;
		this.eventBus = eventBus;
		this.syncCollector = syncCollector;
	}

	public PipelineEventBus getEventBus() {
		return eventBus;
	}

	public LoomBulkSyncCollector getSyncCollector() {
		return syncCollector;
	}

	@Override
	public PipelineResult execute(Pipeline pipeline, LoomMedia media) {
		return execute(pipeline, Flowable.just(media)).blockingFirst();
	}

	@Override
	public Flowable<PipelineResult> execute(Pipeline pipeline, Flowable<LoomMedia> media) {
		if (!pipeline.isEnabled()) {
			return media.map(m -> new PipelineResult(pipeline.name(), m, Map.of(), 0, pipeline.isDryRun()));
		}

		List<PipelineNode> nodes = pipeline.nodes();

		// Initialize nodes and create per-node semaphores on first use
		for (PipelineNode node : nodes) {
			node.initialize();
			nodeSemaphores.computeIfAbsent(node.id(), k -> new Semaphore(node.concurrency()));
			nodeProcessedCounts.computeIfAbsent(node.id(), k -> new AtomicLong());
			nodeFailedCounts.computeIfAbsent(node.id(), k -> new AtomicLong());
		}

		eventBus.publishTracking(new PipelineTrackingEvent(
				Type.PIPELINE_STARTED, pipeline.name(), null, null));

		// Start periodic NODE_STATS emission
		statsScheduler.scheduleAtFixedRate(() -> emitNodeStats(pipeline), STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS);

		return media.flatMap(
				m -> executeSingle(pipeline, m)
						.toFlowable()
						.subscribeOn(Schedulers.io()),
				maxConcurrentMedia)
				.doOnComplete(() -> {
					eventBus.publishTracking(new PipelineTrackingEvent(
							Type.PIPELINE_COMPLETED, pipeline.name(), null, null));
					// Stop stats emission when pipeline completes
					statsScheduler.shutdown();
				});
	}

	/**
	 * Build and execute the per-media-item DAG using {@code Single<NodeResult>} composition.
	 */
	private Single<PipelineResult> executeSingle(Pipeline pipeline, LoomMedia media) {
		return Single.defer(() -> {
			long start = System.currentTimeMillis();

			Map<String, Single<NodeResult>> nodeSingles = new LinkedHashMap<>();
			List<PipelineNode> nodes = pipeline.nodes();

			for (PipelineNode node : nodes) {
				Single<NodeResult> single = buildNodeSingle(pipeline, node, media, nodeSingles);
				nodeSingles.put(node.id(), single);
			}

			// Zip all node results into a single PipelineResult
			List<String> nodeIds = nodes.stream().map(PipelineNode::id).toList();
			List<Single<NodeResult>> singles = nodeIds.stream()
					.map(nodeSingles::get)
					.toList();

			return Single.zip(singles, results -> {
				Map<String, NodeResult> resultMap = new LinkedHashMap<>();
				for (int i = 0; i < results.length; i++) {
					resultMap.put(nodeIds.get(i), (NodeResult) results[i]);
				}
				long elapsed = System.currentTimeMillis() - start;
				return new PipelineResult(pipeline.name(), media, resultMap, elapsed, pipeline.isDryRun());
			});
		});
	}

	/**
	 * Build a cached {@code Single<NodeResult>} for a single node in the per-media DAG.
	 * Dependencies are resolved via {@code Single.zip} on the parent node singles.
	 */
	private Single<NodeResult> buildNodeSingle(Pipeline pipeline, PipelineNode node, LoomMedia media,
			Map<String, Single<NodeResult>> nodeSingles) {

		Set<String> deps = node.dependencies();

		// Resolve upstream results
		Single<Map<String, NodeResult>> upstreamSingle;
		if (deps.isEmpty()) {
			upstreamSingle = Single.just(Collections.emptyMap());
		} else {
			List<String> depIds = new ArrayList<>(deps);
			List<Single<NodeResult>> depSingles = depIds.stream()
					.map(nodeSingles::get)
					.filter(Objects::nonNull)
					.toList();

			if (depSingles.isEmpty()) {
				upstreamSingle = Single.just(Collections.emptyMap());
			} else {
				upstreamSingle = Single.zip(depSingles, results -> {
					Map<String, NodeResult> map = new LinkedHashMap<>();
					for (int i = 0; i < results.length; i++) {
						map.put(depIds.get(i), (NodeResult) results[i]);
					}
					return map;
				});
			}
		}

		return upstreamSingle.flatMap(upstream -> {
			// Check if any blocking dependency failed → skip
			for (String depId : node.dependencies()) {
				NodeResult depResult = upstream.get(depId);
				if (depResult != null && depResult.getState() == NodeState.FAILED && node.isBlocking()) {
					NodeResult skipped = NodeResult.skipped(node.id(), "Dependency " + depId + " failed");
					eventBus.publish(new NodeCompletionEvent(node.id(), media, skipped));
					emitTrackingEvent(Type.NODE_SKIPPED, pipeline.name(), node.id(), media,
							0, "Dependency " + depId + " failed");
					return Single.just(skipped);
				}
			}

			// Check conditional (filter branch) dependencies
			Map<String, FilterBranch> conditions = node.conditionalDependencies();
			for (Map.Entry<String, FilterBranch> entry : conditions.entrySet()) {
				String depId = entry.getKey();
				FilterBranch required = entry.getValue();
				if (required == FilterBranch.ANY) {
					continue;
				}
				NodeResult depResult = upstream.get(depId);
				if (depResult != null && depResult.getState() == NodeState.COMPLETED) {
					Boolean passed = depResult.getOutput(PipelineNode.FILTER_PASSED);
					if (passed != null) {
						boolean branchMatch = (required == FilterBranch.PASS) == passed;
						if (!branchMatch) {
							String reason = "Filter branch mismatch: " + depId + " "
									+ (passed ? "PASS" : "REJECT") + " vs required " + required;
							NodeResult skipped = NodeResult.skipped(node.id(), reason);
							eventBus.publish(new NodeCompletionEvent(node.id(), media, skipped));
							emitTrackingEvent(Type.NODE_SKIPPED, pipeline.name(), node.id(), media,
									0, reason);
							return Single.just(skipped);
						}
					}
				}
			}

			return executeNodeReactive(pipeline, node, media, upstream);
		})
		// cache() ensures the Single executes only once even with multiple downstream subscribers
		.cache();
	}

	/**
	 * Execute a single node on a single media item, respecting per-node concurrency
	 * via semaphore, cache, dry-run, and sync-to-loom semantics.
	 */
	private Single<NodeResult> executeNodeReactive(Pipeline pipeline, PipelineNode node,
			LoomMedia media, Map<String, NodeResult> upstream) {

		return Single.<NodeResult>fromCallable(() -> {
			Semaphore semaphore = nodeSemaphores.get(node.id());

			// Emit NODE_BUFFERED if the semaphore is not immediately available
			if (semaphore.availablePermits() == 0) {
				emitTrackingEvent(Type.NODE_BUFFERED, pipeline.name(), node.id(), media, 0, null);
			}

			semaphore.acquire();
			try {
				emitTrackingEvent(Type.NODE_STARTED, pipeline.name(), node.id(), media, 0, null);

				// Check cache
				NodeCacheProvider cache = node.cacheProvider() != null ? node.cacheProvider() : NoOpNodeCache.INSTANCE;
				Optional<NodeResult> cached = cache.get(node.id(), media);
				if (cached.isPresent()) {
					log.debug("Cache hit for node {} on media {}", node.id(), media.absolutePath());
					return cached.get();
				}

				// Dry-run
				if (pipeline.isDryRun()) {
					log.info("[DRY-RUN] Would execute node {} on {}", node.id(), media.absolutePath());
					return NodeResult.skipped(node.id(), "dry-run");
				}

				// Execute
				log.debug("Executing node {} on {}", node.id(), media.absolutePath());
				NodeResult result = node.process(media, upstream);

				// Cache on success + collect for Loom sync
				if (result.getState() == NodeState.COMPLETED) {
					cache.put(node.id(), media, result);
					if (node.syncToLoom() && syncCollector != null) {
						syncCollector.collect(media, node.id(), result);
					}
				}

				return result;
			} finally {
				semaphore.release();
			}
		})
		.subscribeOn(Schedulers.io())
		.doOnSuccess(result -> {
			eventBus.publish(new NodeCompletionEvent(node.id(), media, result));
			Type trackingType = switch (result.getState()) {
				case COMPLETED -> {
					nodeProcessedCounts.get(node.id()).incrementAndGet();
					yield Type.NODE_COMPLETED;
				}
				case FAILED -> {
					nodeFailedCounts.get(node.id()).incrementAndGet();
					yield Type.NODE_FAILED;
				}
				case SKIPPED -> Type.NODE_SKIPPED;
				default -> null;
			};
			if (trackingType != null) {
				emitTrackingEvent(trackingType, pipeline.name(), node.id(), media,
						result.getDurationMs(), result.getMessage());
			}
		})
		.onErrorReturn(e -> {
			log.error("Node {} failed on {}: {}", node.id(), media.absolutePath(), e.getMessage(), e);
			nodeFailedCounts.get(node.id()).incrementAndGet();
			emitTrackingEvent(Type.NODE_FAILED, pipeline.name(), node.id(), media, 0, e.getMessage());
			return NodeResult.failed(node.id(), 0, e.getMessage());
		});
	}

	@Override
	public int flushSync() {
		if (syncCollector != null) {
			return syncCollector.flush();
		}
		return 0;
	}

	@Override
	public void shutdown() {
		eventBus.clear();
		statsScheduler.shutdown();
	}

	/**
	 * Emits periodic NODE_STATS tracking events for all nodes in the pipeline.
	 * Captures active permits (concurrency - availablePermits), pending queue depth,
	 * and running processed/failed totals.
	 */
	private void emitNodeStats(Pipeline pipeline) {
		for (PipelineNode node : pipeline.nodes()) {
			Semaphore semaphore = nodeSemaphores.get(node.id());
			if (semaphore == null) {
				continue;
			}

			int concurrency = node.concurrency();
			int availablePermits = semaphore.availablePermits();
			int active = concurrency - availablePermits;
			long processed = nodeProcessedCounts.get(node.id()).get();
			long failed = nodeFailedCounts.get(node.id()).get();

			// Only emit if there's activity (active > 0 or processed/failed > 0)
			if (active > 0 || processed > 0 || failed > 0) {
				String message = String.format("active=%d pending=%d processed=%d failed=%d",
						active, 0, processed, failed); // pending queue depth not directly available
				emitTrackingEvent(Type.NODE_STATS, pipeline.name(), node.id(), null, 0, message);
			}
		}
	}

	private void emitTrackingEvent(Type type, String pipelineName, String nodeId,
			LoomMedia media, long durationMs, String message) {
		String mediaPath = media != null ? media.absolutePath().toString() : null;
		eventBus.publishTracking(new PipelineTrackingEvent(type, pipelineName, nodeId,
				mediaPath, durationMs, message));
	}
}
