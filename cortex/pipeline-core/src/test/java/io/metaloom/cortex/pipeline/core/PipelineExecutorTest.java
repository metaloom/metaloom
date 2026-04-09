package io.metaloom.cortex.pipeline.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.media.MediaType;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.common.cache.HeapNodeCache;
import io.metaloom.cortex.pipeline.common.event.DefaultPipelineEventBus;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector;
import io.metaloom.cortex.pipeline.core.executor.DAGPipelineExecutor;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.core.node.LoomFetchNode;
import io.metaloom.utils.hash.SHA512;

/**
 * Test that demonstrates pipeline construction, DAG-based execution with dependency resolution,
 * parallel node scheduling, per-node concurrency control, caching, dry-run mode, and event bus.
 */
class PipelineExecutorTest {

	private static final Logger log = LoggerFactory.getLogger(PipelineExecutorTest.class);

	private DAGPipelineExecutor executor;

	@BeforeEach
	void setUp() {
		executor = new DAGPipelineExecutor(8);
	}

	@AfterEach
	void tearDown() {
		executor.shutdown();
	}

	/**
	 * Full pipeline: hash -> (tika | fingerprint | thumbnail | loom-fetch) -> llm -> sync
	 * Demonstrates DAG execution, parallelism, dependencies, and event bus notifications.
	 */
	@Test
	void testFullPipelineExecution() {
		// Track execution order
		CopyOnWriteArrayList<String> executionLog = new CopyOnWriteArrayList<>();

		// Build nodes with realistic dependencies
		PipelineNode hashNode = new TestNode("sha512", "SHA-512 Hash", NodeMode.PARALLEL, true,
				Set.of(), 4, 50, executionLog, false, true);

		PipelineNode tikaNode = new TestNode("tika", "Tika Analysis", NodeMode.PARALLEL, false,
				Set.of("sha512"), 2, 30, executionLog);

		PipelineNode fingerprintNode = new TestNode("fingerprint", "Video Fingerprint", NodeMode.PARALLEL, false,
				Set.of("sha512"), 2, 80, executionLog);

		PipelineNode thumbnailNode = new TestNode("thumbnail", "Thumbnail Generation", NodeMode.PARALLEL, false,
				Set.of("sha512"), 2, 40, executionLog);

		PipelineNode loomFetchNode = new LoomFetchNode(Set.of("sha512"), 2, media -> {
			executionLog.add("loom-fetch");
			Thread.sleep(20);
		});

		PipelineNode llmNode = new TestNode("llm", "LLM Analysis", NodeMode.PARALLEL, true,
				Set.of("tika", "thumbnail", "loom-fetch"), 4, 100, executionLog);

		PipelineNode syncNode = new TestNode("loom-sync", "Loom Sync", NodeMode.SEQUENTIAL, true,
				Set.of("sha512", "tika", "fingerprint", "thumbnail", "llm"), 1, 30, executionLog);

		// Build pipeline
		Pipeline pipeline = DefaultPipeline.builder("video-full-analysis")
				.description("Full processing for video libraries")
				.priority(100)
				.addNode(hashNode)
				.addNode(tikaNode)
				.addNode(fingerprintNode)
				.addNode(thumbnailNode)
				.addNode(loomFetchNode)
				.addNode(llmNode)
				.addNode(syncNode)
				.build();

		// Subscribe to all events on the bus
		List<String> eventLog = new CopyOnWriteArrayList<>();
		executor.getEventBus().subscribeAll(event -> {
			eventLog.add(event.getNodeId() + ":" + event.getResult().getState());
		});

		// Execute
		LoomMedia media = new StubLoomMedia("/media/videos/test.mp4", true);
		PipelineResult result = executor.execute(pipeline, media);

		// Verify pipeline succeeded
		assertTrue(result.isSuccess(), "Pipeline should succeed: " + result);
		assertFalse(result.isDryRun());
		assertEquals("video-full-analysis", result.getPipelineName());
		assertEquals(7, result.getNodeResults().size());

		// Verify all nodes completed
		for (Map.Entry<String, NodeResult> entry : result.getNodeResults().entrySet()) {
			assertEquals(NodeState.COMPLETED, entry.getValue().getState(),
					"Node " + entry.getKey() + " should be COMPLETED");
		}

		// Verify dependency ordering: sha512 must come before everything else
		int hashIdx = executionLog.indexOf("sha512");
		int tikaIdx = executionLog.indexOf("tika");
		int fpIdx = executionLog.indexOf("fingerprint");
		int thumbIdx = executionLog.indexOf("thumbnail");
		int loomFetchIdx = executionLog.indexOf("loom-fetch");
		int llmIdx = executionLog.indexOf("llm");
		int syncIdx = executionLog.indexOf("loom-sync");

		assertTrue(hashIdx < tikaIdx, "sha512 before tika");
		assertTrue(hashIdx < fpIdx, "sha512 before fingerprint");
		assertTrue(hashIdx < thumbIdx, "sha512 before thumbnail");
		assertTrue(hashIdx < loomFetchIdx, "sha512 before loom-fetch");
		assertTrue(tikaIdx < llmIdx, "tika before llm");
		assertTrue(thumbIdx < llmIdx, "thumbnail before llm");
		assertTrue(loomFetchIdx < llmIdx, "loom-fetch before llm");
		assertTrue(llmIdx < syncIdx, "llm before loom-sync");

		// Verify event bus captured all completions
		assertEquals(7, eventLog.size(), "Should have 7 completion events");
		log.info("Execution order: {}", executionLog);
		log.info("Events: {}", eventLog);
		log.info("Result: {}", result);
	}

	@Test
	void testParallelNodeExecution() throws InterruptedException {
		// Verify that independent nodes actually run in parallel
		AtomicInteger maxConcurrent = new AtomicInteger(0);
		AtomicInteger currentConcurrent = new AtomicInteger(0);
		CountDownLatch allStarted = new CountDownLatch(3);

		PipelineNode node1 = createConcurrencyTestNode("node-a", Set.of(), currentConcurrent, maxConcurrent, allStarted);
		((AbstractPipelineNode) node1).setSource(true);
		PipelineNode node2 = createConcurrencyTestNode("node-b", Set.of("node-a"), currentConcurrent, maxConcurrent, allStarted);
		PipelineNode node3 = createConcurrencyTestNode("node-c", Set.of("node-a"), currentConcurrent, maxConcurrent, allStarted);

		Pipeline pipeline = DefaultPipeline.builder("parallel-test")
				.addNode(node1)
				.addNode(node2)
				.addNode(node3)
				.build();

		LoomMedia media = new StubLoomMedia("/test/file.mp4", true);
		PipelineResult result = executor.execute(pipeline, media);

		assertTrue(result.isSuccess());
		// With 8 threads and 3 independent nodes, all 3 should run concurrently
		assertTrue(maxConcurrent.get() >= 2, "At least 2 nodes should run in parallel, got: " + maxConcurrent.get());
		log.info("Max concurrent nodes: {}", maxConcurrent.get());
	}

	@Test
	void testPerNodeConcurrencyLimiting() {
		// Verify that per-node semaphore limits concurrency
		AtomicInteger maxConcurrentForNode = new AtomicInteger(0);
		AtomicInteger currentConcurrentForNode = new AtomicInteger(0);

		// Node with concurrency=1 processing a stream of 5 items
		AbstractPipelineNode limitedNode = new AbstractPipelineNode("limited", "Limited Node",
				NodeMode.PARALLEL, true, Set.of(), 1) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				int c = currentConcurrentForNode.incrementAndGet();
				maxConcurrentForNode.updateAndGet(max -> Math.max(max, c));
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				currentConcurrentForNode.decrementAndGet();
				return NodeResult.success(id(), 50);
			}
		};
		limitedNode.setSource(true);

		Pipeline pipeline = DefaultPipeline.builder("concurrency-test")
				.addNode(limitedNode)
				.build();

		// Process stream of 5 media items
		List<PipelineResult> results = executor.execute(pipeline,
				Stream.of(
						new StubLoomMedia("/a.mp4", true),
						new StubLoomMedia("/b.mp4", true),
						new StubLoomMedia("/c.mp4", true),
						new StubLoomMedia("/d.mp4", true),
						new StubLoomMedia("/e.mp4", true)))
				.toList();

		assertEquals(5, results.size());
		// Since stream is sequential by default, concurrency=1 is expected
		assertEquals(1, maxConcurrentForNode.get(),
				"Node with concurrency=1 should not exceed 1 concurrent execution");
	}

	@Test
	void testCaching() {
		HeapNodeCache cache = new HeapNodeCache(100, 60);
		AtomicInteger processCount = new AtomicInteger(0);

		AbstractPipelineNode cachedNode = new AbstractPipelineNode("cached", "Cached Node",
				NodeMode.PARALLEL, true, Set.of(), 1) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				processCount.incrementAndGet();
				return NodeResult.success(id(), 10);
			}

			@Override
			public io.metaloom.cortex.pipeline.api.cache.NodeCacheProvider cacheProvider() {
				return cache;
			}
		};
		cachedNode.setSource(true);

		Pipeline pipeline = DefaultPipeline.builder("cache-test")
				.addNode(cachedNode)
				.build();

		LoomMedia media = new StubLoomMedia("/test/cached.mp4", true);

		// First execution — cache miss, should process
		PipelineResult result1 = executor.execute(pipeline, media);
		assertTrue(result1.isSuccess());
		assertEquals(1, processCount.get());

		// Second execution — cache hit, should skip processing
		PipelineResult result2 = executor.execute(pipeline, media);
		assertTrue(result2.isSuccess());
		assertEquals(1, processCount.get(), "Node should not process again due to cache hit");
	}

	@Test
	void testDryRunMode() {
		AtomicInteger processCount = new AtomicInteger(0);

		AbstractPipelineNode node = new AbstractPipelineNode("action", "Some Node",
				NodeMode.SEQUENTIAL, true, Set.of(), 1) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				processCount.incrementAndGet();
				return NodeResult.success(id(), 10);
			}
		};
		node.setSource(true);

		Pipeline pipeline = DefaultPipeline.builder("dryrun-test")
				.dryRun(true)
				.addNode(node)
				.build();

		LoomMedia media = new StubLoomMedia("/test/dry.mp4", true);
		PipelineResult result = executor.execute(pipeline, media);

		assertTrue(result.isDryRun());
		assertEquals(0, processCount.get(), "No nodes should process in dry-run mode");
		assertEquals(NodeState.SKIPPED, result.getNodeResults().get("action").getState());
	}

	@Test
	void testDisabledPipeline() {
		AbstractPipelineNode node = new AbstractPipelineNode("action", "Some Node",
				NodeMode.SEQUENTIAL, true, Set.of(), 1) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				return NodeResult.success(id(), 10);
			}
		};
		node.setSource(true);

		Pipeline pipeline = DefaultPipeline.builder("disabled-test")
				.enabled(false)
				.addNode(node)
				.build();

		LoomMedia media = new StubLoomMedia("/test/disabled.mp4", true);
		PipelineResult result = executor.execute(pipeline, media);

		assertTrue(result.getNodeResults().isEmpty(), "Disabled pipeline should not run any nodes");
	}

	@Test
	void testPipelineManager() {
		DefaultPipelineManager manager = new DefaultPipelineManager();

		Pipeline videoPipeline = DefaultPipeline.builder("video-full")
				.priority(100)
				.addNode(new TestNode("hash", "Hash", NodeMode.PARALLEL, true, Set.of(), 4, 10, new CopyOnWriteArrayList<>(), false, true))
				.build();

		Pipeline imagePipeline = DefaultPipeline.builder("image-standard")
				.priority(50)
				.addNode(new TestNode("hash", "Hash", NodeMode.PARALLEL, true, Set.of(), 4, 10, new CopyOnWriteArrayList<>(), false, true))
				.build();

		Pipeline fallback = DefaultPipeline.builder("hash-only")
				.priority(0)
				.addNode(new TestNode("hash", "Hash", NodeMode.PARALLEL, true, Set.of(), 4, 10, new CopyOnWriteArrayList<>(), false, true))
				.build();

		manager.register(videoPipeline);
		manager.register(imagePipeline);
		manager.register(fallback);

		assertEquals(3, manager.pipelines().size());

		// Video file should resolve to highest priority pipeline
		LoomMedia videoMedia = new StubLoomMedia("/test/video.mp4", true);
		Pipeline resolved = manager.resolve(videoMedia).orElse(null);
		assertNotNull(resolved);
		assertEquals("video-full", resolved.name());

		// Image file also resolves to highest priority (filtering is now done via filter nodes)
		LoomMedia imageMedia = new StubLoomMedia("/test/photo.jpg", false) {
			@Override
			public boolean isImage() {
				return true;
			}
		};
		resolved = manager.resolve(imageMedia).orElse(null);
		assertNotNull(resolved);
		assertEquals("video-full", resolved.name());
	}

	@Test
	void testDependencyCycleDetection() {
		// Nodes with circular dependencies should throw
		PipelineNode a = new TestNode("a", "A", NodeMode.PARALLEL, true, Set.of("b"), 1, 10, new CopyOnWriteArrayList<>(), false, true);
		PipelineNode b = new TestNode("b", "B", NodeMode.PARALLEL, true, Set.of("a"), 1, 10, new CopyOnWriteArrayList<>());

		assertThrows(IllegalStateException.class, () -> {
			DefaultPipeline.builder("cycle-test")
					.addNode(a)
					.addNode(b)
					.build();
		});
	}

	@Test
	void testEventBusNotifications() {
		DefaultPipelineEventBus eventBus = new DefaultPipelineEventBus();
		DAGPipelineExecutor evExecutor = new DAGPipelineExecutor(4, eventBus);

		List<String> events = new CopyOnWriteArrayList<>();
		List<String> specificEvents = new CopyOnWriteArrayList<>();

		eventBus.subscribeAll(e -> events.add(e.getNodeId()));
		eventBus.subscribe("hash", e -> specificEvents.add(e.getNodeId() + ":" + e.getResult().getState()));

		PipelineNode hashNode = new TestNode("hash", "Hash", NodeMode.PARALLEL, true,
				Set.of(), 4, 10, new CopyOnWriteArrayList<>(), false, true);
		PipelineNode syncNode = new TestNode("sync", "Sync", NodeMode.SEQUENTIAL, true,
				Set.of("hash"), 1, 10, new CopyOnWriteArrayList<>());

		Pipeline pipeline = DefaultPipeline.builder("event-test")
				.addNode(hashNode)
				.addNode(syncNode)
				.build();

		LoomMedia media = new StubLoomMedia("/test/event.mp4", true);
		evExecutor.execute(pipeline, media);

		assertEquals(2, events.size());
		assertTrue(events.contains("hash"));
		assertTrue(events.contains("sync"));
		assertEquals(1, specificEvents.size());
		assertEquals("hash:COMPLETED", specificEvents.get(0));

		evExecutor.shutdown();
	}

	/**
	 * Complex DAG demonstrating the user's target pipeline:
	 *
	 * <pre>
	 * hasher -> thumbnail    -> llm-image-desc (extracts image description via prompt)
	 *                              -> llm-process-desc (processes the image description)
	 *        -> fingerprint  (parallel with thumbnail and whisper)
	 *        -> whisper      -> llm-transcript-qa (answers questions from transcript)
	 * </pre>
	 *
	 * Multiple LLM nodes with distinct IDs, each depending on different upstream producers.
	 * Nodes pass data downstream via NodeResult output maps.
	 */
	@Test
	void testComplexDAGWithMultipleLLMNodes() {
		CopyOnWriteArrayList<String> executionLog = new CopyOnWriteArrayList<>();

		// 1. Hasher — root node, no dependencies, source node
		PipelineNode hasherNode = new TestNode("hasher", "SHA-512 Hash", NodeMode.PARALLEL, true,
				Set.of(), 4, 30, executionLog, false, true);

		// 2. Thumbnail — depends on hasher, produces image data
		PipelineNode thumbnailNode = new OutputTestNode("thumbnail", "Thumbnail", NodeMode.PARALLEL, true,
				Set.of("hasher"), 2, 40,
				Map.of("image", "/tmp/thumb_001.jpg"),
				executionLog);

		// 3. Fingerprint — depends on hasher, runs in parallel with thumbnail & whisper
		PipelineNode fingerprintNode = new TestNode("fingerprint", "Fingerprint", NodeMode.PARALLEL, true,
				Set.of("hasher"), 2, 60, executionLog, true);

		// 4. Whisper — depends on hasher, produces transcript
		PipelineNode whisperNode = new OutputTestNode("whisper", "Whisper STT", NodeMode.PARALLEL, true,
				Set.of("hasher"), 1, 80,
				Map.of("transcript", "Hello world, this is a test video about machine learning."),
				executionLog);

		// 5. LLM Image Description — depends on thumbnail, reads its output, produces description
		PipelineNode llmImageDescNode = new AbstractPipelineNode("llm-image-desc", "LLM Image Description",
				NodeMode.PARALLEL, true, Set.of("thumbnail"), 4, true) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				// Read thumbnail path from upstream
				NodeResult thumbResult = upstreamResults.get("thumbnail");
				String imagePath = thumbResult != null ? thumbResult.getOutput("image") : "unknown";
				String description = "A scenic landscape with mountains (from " + imagePath + ")";
				executionLog.add(id());
				return NodeResult.success(id(), 50, Map.of("description", description));
			}
		};

		// 6. LLM Process Description — depends on llm-image-desc, reads the description
		PipelineNode llmProcessDescNode = new AbstractPipelineNode("llm-process-desc", "LLM Process Description",
				NodeMode.PARALLEL, true, Set.of("llm-image-desc"), 4, true) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				NodeResult descResult = upstreamResults.get("llm-image-desc");
				String description = descResult != null ? descResult.getOutput("description") : "none";
				String tags = "nature,landscape,mountains (derived from: " + description + ")";
				executionLog.add(id());
				return NodeResult.success(id(), 30, Map.of("tags", tags));
			}
		};

		// 7. LLM Transcript QA — depends on whisper, reads transcript
		PipelineNode llmTranscriptQaNode = new AbstractPipelineNode("llm-transcript-qa", "LLM Transcript QA",
				NodeMode.PARALLEL, true, Set.of("whisper"), 4, true) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				NodeResult whisperResult = upstreamResults.get("whisper");
				String transcript = whisperResult != null ? whisperResult.getOutput("transcript") : "none";
				String answer = "This video discusses machine learning (from transcript: " + transcript.substring(0, 20) + "...)";
				executionLog.add(id());
				return NodeResult.success(id(), 50, Map.of("answer", answer));
			}
		};

		Pipeline pipeline = DefaultPipeline.builder("complex-llm-pipeline")
				.description("Complex pipeline with multiple LLM nodes")
				.priority(100)
				.addNode(hasherNode)
				.addNode(thumbnailNode)
				.addNode(fingerprintNode)
				.addNode(whisperNode)
				.addNode(llmImageDescNode)
				.addNode(llmProcessDescNode)
				.addNode(llmTranscriptQaNode)
				.build();

		LoomMedia media = new StubLoomMedia("/media/videos/complex.mp4", true);
		PipelineResult result = executor.execute(pipeline, media);

		// Verify all nodes completed
		assertTrue(result.isSuccess(), "Pipeline should succeed: " + result);
		assertEquals(7, result.getNodeResults().size());

		// Verify dependency ordering
		int hasherIdx = executionLog.indexOf("hasher");
		int thumbIdx = executionLog.indexOf("thumbnail");
		int fpIdx = executionLog.indexOf("fingerprint");
		int whisperIdx = executionLog.indexOf("whisper");
		int llmImgIdx = executionLog.indexOf("llm-image-desc");
		int llmProcIdx = executionLog.indexOf("llm-process-desc");
		int llmQaIdx = executionLog.indexOf("llm-transcript-qa");

		// hasher must come first
		assertTrue(hasherIdx < thumbIdx, "hasher before thumbnail");
		assertTrue(hasherIdx < fpIdx, "hasher before fingerprint");
		assertTrue(hasherIdx < whisperIdx, "hasher before whisper");

		// thumbnail -> llm-image-desc -> llm-process-desc
		assertTrue(thumbIdx < llmImgIdx, "thumbnail before llm-image-desc");
		assertTrue(llmImgIdx < llmProcIdx, "llm-image-desc before llm-process-desc");

		// whisper -> llm-transcript-qa
		assertTrue(whisperIdx < llmQaIdx, "whisper before llm-transcript-qa");

		// Verify output data flows correctly through the chain
		NodeResult llmImageResult = result.getNodeResults().get("llm-image-desc");
		assertNotNull(llmImageResult.getOutput("description"));
		assertTrue(((String) llmImageResult.getOutput("description")).contains("scenic landscape"));

		NodeResult llmProcessResult = result.getNodeResults().get("llm-process-desc");
		assertNotNull(llmProcessResult.getOutput("tags"));
		assertTrue(((String) llmProcessResult.getOutput("tags")).contains("nature"));

		NodeResult llmQaResult = result.getNodeResults().get("llm-transcript-qa");
		assertNotNull(llmQaResult.getOutput("answer"));
		assertTrue(((String) llmQaResult.getOutput("answer")).contains("machine learning"));

		log.info("Execution order: {}", executionLog);
		log.info("LLM image output: {}", llmImageResult.getOutput());
		log.info("LLM process output: {}", llmProcessResult.getOutput());
		log.info("LLM QA output: {}", llmQaResult.getOutput());
		log.info("Result: {}", result);
	}

	@Test
	void testSyncToLoomFlag() {
		// Nodes with syncToLoom=true should be collected by the bulk sync collector
		PipelineNode hashNode = new TestNode("hash", "Hash", NodeMode.PARALLEL, true,
				Set.of(), 4, 10, new CopyOnWriteArrayList<>(), true, true);
		PipelineNode thumbnailNode = new TestNode("thumbnail", "Thumb", NodeMode.PARALLEL, true,
				Set.of("hash"), 2, 10, new CopyOnWriteArrayList<>(), true);
		PipelineNode internalNode = new TestNode("internal", "Internal", NodeMode.PARALLEL, true,
				Set.of("hash"), 2, 10, new CopyOnWriteArrayList<>(), false);

		Pipeline pipeline = DefaultPipeline.builder("sync-test")
				.addNode(hashNode)
				.addNode(thumbnailNode)
				.addNode(internalNode)
				.build();

		assertTrue(hashNode.syncToLoom());
		assertTrue(thumbnailNode.syncToLoom());
		assertFalse(internalNode.syncToLoom());
	}

	@Test
	void testBulkSyncCollectorIntegration() {
		// Set up a sync collector that records what gets flushed
		List<DefaultLoomBulkSyncCollector.SyncEntry> flushedEntries = new CopyOnWriteArrayList<>();
		DefaultLoomBulkSyncCollector syncCollector = new DefaultLoomBulkSyncCollector(
				batch -> flushedEntries.addAll(batch), 50);

		DAGPipelineExecutor syncExecutor = new DAGPipelineExecutor(4,
				new DefaultPipelineEventBus(), syncCollector);

		// hash (sync) -> thumbnail (sync) -> internal (no sync)
		PipelineNode hashNode2 = new TestNode("hash", "Hash", NodeMode.PARALLEL, true,
				Set.of(), 4, 10, new CopyOnWriteArrayList<>(), true, true);
		PipelineNode thumbnailNode2 = new TestNode("thumbnail", "Thumb", NodeMode.PARALLEL, true,
				Set.of("hash"), 2, 10, new CopyOnWriteArrayList<>(), true);
		PipelineNode internalNode2 = new TestNode("internal", "Internal", NodeMode.PARALLEL, true,
				Set.of("hash"), 2, 10, new CopyOnWriteArrayList<>(), false);

		Pipeline pipeline2 = DefaultPipeline.builder("bulk-sync-test")
				.addNode(hashNode2)
				.addNode(thumbnailNode2)
				.addNode(internalNode2)
				.build();

		// Process a batch of 3 media items
		List<LoomMedia> batch = List.of(
				new StubLoomMedia("/a.mp4", true),
				new StubLoomMedia("/b.mp4", true),
				new StubLoomMedia("/c.mp4", true));

		List<PipelineResult> results = syncExecutor.executeBatch(pipeline2, batch);

		assertEquals(3, results.size());
		assertTrue(results.stream().allMatch(PipelineResult::isSuccess));

		// 3 media x 2 sync-eligible nodes = 6 flushed entries
		assertEquals(6, flushedEntries.size(), "Should flush 6 sync entries (3 media x 2 sync nodes)");

		// Verify only hash and thumbnail entries (not internal)
		assertTrue(flushedEntries.stream().allMatch(
				e -> "hash".equals(e.getNodeId()) || "thumbnail".equals(e.getNodeId())),
				"Only sync-eligible nodes should be flushed");

		syncExecutor.shutdown();
	}

	// --- Helper classes ---

	/**
	 * Test node that simulates work with a configurable delay and logs execution order.
	 */
	static class TestNode extends AbstractPipelineNode {
		private final long delayMs;
		private final List<String> executionLog;
		private final boolean source;

		TestNode(String id, String name, NodeMode mode, boolean blocking,
				Set<String> dependencies, int concurrency, long delayMs, List<String> executionLog) {
			this(id, name, mode, blocking, dependencies, concurrency, delayMs, executionLog, false, false);
		}

		TestNode(String id, String name, NodeMode mode, boolean blocking,
				Set<String> dependencies, int concurrency, long delayMs, List<String> executionLog,
				boolean syncToLoom) {
			this(id, name, mode, blocking, dependencies, concurrency, delayMs, executionLog, syncToLoom, false);
		}

		TestNode(String id, String name, NodeMode mode, boolean blocking,
				Set<String> dependencies, int concurrency, long delayMs, List<String> executionLog,
				boolean syncToLoom, boolean source) {
			super(id, name, mode, blocking, dependencies, concurrency, syncToLoom);
			this.delayMs = delayMs;
			this.executionLog = executionLog;
			this.source = source;
		}

		@Override
		public boolean isSource() {
			return source;
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			try {
				Thread.sleep(delayMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return NodeResult.failed(id(), 0, "Interrupted");
			}
			executionLog.add(id());
			return NodeResult.success(id(), delayMs);
		}
	}

	/**
	 * Test node that produces output data so downstream nodes can read from it.
	 */
	static class OutputTestNode extends AbstractPipelineNode {
		private final long delayMs;
		private final Map<String, Object> outputData;
		private final List<String> executionLog;

		OutputTestNode(String id, String name, NodeMode mode, boolean blocking,
				Set<String> dependencies, int concurrency, long delayMs,
				Map<String, Object> outputData, List<String> executionLog) {
			super(id, name, mode, blocking, dependencies, concurrency, true);
			this.delayMs = delayMs;
			this.outputData = outputData;
			this.executionLog = executionLog;
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			try {
				Thread.sleep(delayMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return NodeResult.failed(id(), 0, "Interrupted");
			}
			executionLog.add(id());
			return NodeResult.success(id(), delayMs, outputData);
		}
	}

	private PipelineNode createConcurrencyTestNode(String id, Set<String> deps,
			AtomicInteger currentConcurrent, AtomicInteger maxConcurrent, CountDownLatch allStarted) {
		return new AbstractPipelineNode(id, id, NodeMode.PARALLEL, true, deps, 1) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				int c = currentConcurrent.incrementAndGet();
				maxConcurrent.updateAndGet(max -> Math.max(max, c));
				allStarted.countDown();
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				currentConcurrent.decrementAndGet();
				return NodeResult.success(id(), 100);
			}
		};
	}

	/**
	 * Minimal LoomMedia stub for testing without filesystem or xattr dependencies.
	 */
	static class StubLoomMedia implements LoomMedia {
		private final String path;
		private final boolean isVideo;
		private SHA512 sha512;

		StubLoomMedia(String path, boolean isVideo) {
			this.path = path;
			this.isVideo = isVideo;
		}

		@Override
		public SHA512 getSHA512() {
			return sha512;
		}

		@Override
		public void setSHA512(SHA512 hash) {
			this.sha512 = hash;
		}

		@Override
		public boolean hasSHA512() {
			return sha512 != null;
		}

		@Override
		public boolean isVideo() {
			return isVideo;
		}

		@Override
		public boolean isImage() {
			return false;
		}

		@Override
		public boolean isAudio() {
			return false;
		}

		@Override
		public boolean isDocument() {
			return false;
		}

		@Override
		public File file() {
			return new File(path);
		}

		@Override
		public Path path() {
			return Path.of(path);
		}

		@Override
		public void setPath(Path path) {
		}

		@Override
		public long size() {
			return 1024 * 1024;
		}

		@Override
		public String absolutePath() {
			return path;
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public <T extends LoomMedia> T of(MediaType<T> type) {
			return null;
		}

		@Override
		public InputStream open() {
			return InputStream.nullInputStream();
		}

		@Override
		public List<String> listXAttr() {
			return List.of();
		}

		@Override
		public MetaStorage storage() {
			return null;
		}

		@Override
		public LoomMedia self() {
			return this;
		}
	}
}
