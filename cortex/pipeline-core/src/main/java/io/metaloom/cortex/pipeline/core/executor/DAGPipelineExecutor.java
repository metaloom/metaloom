package io.metaloom.cortex.pipeline.core.executor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.api.sync.LoomBulkSyncCollector;
import io.metaloom.cortex.pipeline.common.cache.NoOpNodeCache;
import io.metaloom.cortex.pipeline.common.event.DefaultPipelineEventBus;

/**
 * DAG-based pipeline executor. Nodes are scheduled as soon as all their dependencies
 * have completed. Each node has its own bounded semaphore controlling concurrency.
 *
 * <p>The executor uses {@link CompletableFuture} composition to build the execution graph
 * dynamically per media item. An internal {@link PipelineEventBus} is used to notify
 * downstream subscribers of node completion events.</p>
 *
 * <p>Per-node concurrency is controlled via semaphores, allowing independent scaling:
 * e.g., hasher with concurrency 4, whisper with concurrency 1, llm with concurrency 4.</p>
 */
public class DAGPipelineExecutor implements PipelineExecutor {

	private static final Logger log = LoggerFactory.getLogger(DAGPipelineExecutor.class);

	private final ExecutorService executorService;
	private final PipelineEventBus eventBus;
	private final LoomBulkSyncCollector syncCollector;
	private final Map<String, Semaphore> nodeSemaphores = new ConcurrentHashMap<>();

	public DAGPipelineExecutor(int threadPoolSize) {
		this(threadPoolSize, new DefaultPipelineEventBus(), null);
	}

	public DAGPipelineExecutor(int threadPoolSize, PipelineEventBus eventBus) {
		this(threadPoolSize, eventBus, null);
	}

	public DAGPipelineExecutor(int threadPoolSize, PipelineEventBus eventBus, LoomBulkSyncCollector syncCollector) {
		this.executorService = Executors.newFixedThreadPool(threadPoolSize);
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
		long pipelineStart = System.currentTimeMillis();

		if (!pipeline.isEnabled()) {
			return new PipelineResult(pipeline.name(), media, Map.of(), 0, pipeline.isDryRun());
		}

		List<PipelineNode> nodes = pipeline.nodes();

		// Initialize nodes on first use
		for (PipelineNode node : nodes) {
			node.initialize();
			nodeSemaphores.computeIfAbsent(node.id(), k -> new Semaphore(node.concurrency()));
		}

		// Build the CompletableFuture DAG
		Map<String, CompletableFuture<NodeResult>> futures = new HashMap<>();
		ConcurrentHashMap<String, NodeResult> results = new ConcurrentHashMap<>();

		for (PipelineNode node : nodes) {
			CompletableFuture<NodeResult> future = buildNodeFuture(pipeline, node, media, futures, results);
			futures.put(node.id(), future);
		}

		// Wait for all nodes to complete
		CompletableFuture<Void> allDone = CompletableFuture.allOf(
				futures.values().toArray(new CompletableFuture[0]));

		try {
			allDone.join();
		} catch (Exception e) {
			log.error("Pipeline execution failed for {}: {}", pipeline.name(), e.getMessage(), e);
		}

		long elapsed = System.currentTimeMillis() - pipelineStart;
		return new PipelineResult(pipeline.name(), media, new HashMap<>(results), elapsed, pipeline.isDryRun());
	}

	@Override
	public Stream<PipelineResult> execute(Pipeline pipeline, Stream<LoomMedia> mediaStream) {
		return mediaStream.map(media -> execute(pipeline, media));
	}

	private CompletableFuture<NodeResult> buildNodeFuture(Pipeline pipeline, PipelineNode node, LoomMedia media,
			Map<String, CompletableFuture<NodeResult>> futures,
			ConcurrentHashMap<String, NodeResult> results) {

		// Gather dependency futures
		List<CompletableFuture<NodeResult>> depFutures = node.dependencies().stream()
				.map(depId -> futures.get(depId))
				.filter(f -> f != null)
				.collect(Collectors.toList());

		// Create a future that waits for all dependencies, then executes this node
		CompletableFuture<Void> depBarrier;
		if (depFutures.isEmpty()) {
			depBarrier = CompletableFuture.completedFuture(null);
		} else {
			depBarrier = CompletableFuture.allOf(depFutures.toArray(new CompletableFuture[0]));
		}

		return depBarrier.thenComposeAsync(v -> {
			// Check if any dependency failed and this node should be skipped
			Map<String, NodeResult> upstreamResults = new HashMap<>();
			for (String depId : node.dependencies()) {
				NodeResult depResult = results.get(depId);
				if (depResult != null) {
					upstreamResults.put(depId, depResult);
					if (depResult.getState() == NodeState.FAILED && node.isBlocking()) {
						NodeResult skipped = NodeResult.skipped(node.id(), "Dependency " + depId + " failed");
						results.put(node.id(), skipped);
						eventBus.publish(new NodeCompletionEvent(node.id(), media, skipped));
						return CompletableFuture.completedFuture(skipped);
					}
				}
			}

			// Check conditional (filter branch) dependencies
			Map<String, FilterBranch> conditions = node.conditionalDependencies();
			if (!conditions.isEmpty()) {
				for (Map.Entry<String, FilterBranch> entry : conditions.entrySet()) {
					String depId = entry.getKey();
					FilterBranch required = entry.getValue();
					if (required == FilterBranch.ANY) {
						continue;
					}
					NodeResult depResult = results.get(depId);
					if (depResult != null && depResult.getState() == NodeState.COMPLETED) {
						Boolean passed = depResult.getOutput(PipelineNode.FILTER_PASSED);
						if (passed != null) {
							boolean branchMatch = (required == FilterBranch.PASS) == passed;
							if (!branchMatch) {
								String reason = "Filter branch mismatch: " + depId + " "
										+ (passed ? "PASS" : "REJECT") + " vs required " + required;
								NodeResult skipped = NodeResult.skipped(node.id(), reason);
								results.put(node.id(), skipped);
								eventBus.publish(new NodeCompletionEvent(node.id(), media, skipped));
								return CompletableFuture.completedFuture(skipped);
							}
						}
					}
				}
			}

			return CompletableFuture.supplyAsync(() -> {
				Semaphore semaphore = nodeSemaphores.get(node.id());
				try {
					semaphore.acquire();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					NodeResult failed = NodeResult.failed(node.id(), 0, "Interrupted waiting for semaphore");
					results.put(node.id(), failed);
					return failed;
				}
				try {
					NodeResult result = executeNode(pipeline, node, media, upstreamResults);
					results.put(node.id(), result);
					eventBus.publish(new NodeCompletionEvent(node.id(), media, result));
					return result;
				} finally {
					semaphore.release();
				}
			}, executorService);
		}, executorService);
	}

	private NodeResult executeNode(Pipeline pipeline, PipelineNode node, LoomMedia media,
			Map<String, NodeResult> upstreamResults) {

		// Check cache first
		NodeCacheProvider cache = node.cacheProvider() != null ? node.cacheProvider() : NoOpNodeCache.INSTANCE;
		Optional<NodeResult> cached = cache.get(node.id(), media);
		if (cached.isPresent()) {
			log.debug("Cache hit for node {} on media {}", node.id(), media.absolutePath());
			return cached.get();
		}

		// Dry-run mode: skip actual processing
		if (pipeline.isDryRun()) {
			log.info("[DRY-RUN] Would execute node {} on {}", node.id(), media.absolutePath());
			return NodeResult.skipped(node.id(), "dry-run");
		}

		log.debug("Executing node {} on {}", node.id(), media.absolutePath());
		NodeResult result = node.process(media, upstreamResults);

		// Cache the result on success
		if (result.getState() == NodeState.COMPLETED) {
			cache.put(node.id(), media, result);

			// Collect sync-eligible results for bulk Loom sync
			if (node.syncToLoom() && syncCollector != null) {
				syncCollector.collect(media, node.id(), result);
			}
		}

		return result;
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
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
				executorService.shutdownNow();
			}
		} catch (InterruptedException e) {
			executorService.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
