package io.metaloom.loom.cortex.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.rxjava3.core.Flowable;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.cortex.node.tika.TikaNode;
import io.metaloom.cortex.node.tika.TikaNodeOptions;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.executor.ReactivePipelineExecutor;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.core.node.filter.AbstractFilterNode;
import io.metaloom.loom.test.data.TestMedia;

/**
 * Pipeline integration test that uses actual Cortex node implementations
 * (SHA512Node, TikaNode) wired into a DAG pipeline with a filesystem source
 * and a size filter.
 *
 * <pre>
 * source (fs-source) → filter (size-filter) → hash (sha512) + tika (tika)
 * </pre>
 */
class PipelineIntegrationTest extends AbstractMediaTest {

	private static final Logger log = LoggerFactory.getLogger(PipelineIntegrationTest.class);

	private ReactivePipelineExecutor executor;

	@BeforeEach
	void setUpExecutor() {
		executor = new ReactivePipelineExecutor(4);
	}

	@AfterEach
	void tearDownExecutor() {
		executor.shutdown();
	}

	/**
	 * Build a pipeline:
	 * <pre>
	 *   fs-source -> size-filter --(PASS)--> sha512 (parallel)
	 *                             --(PASS)--> tika   (parallel)
	 * </pre>
	 * Process a video file that passes the size filter and verify all nodes complete.
	 */
	@Test
	void testPipelineWithRealNodesOnVideo() throws IOException {
		CortexOptions opts = options();
		TestMedia video = video1();
		LoomMedia media = media(video);

		// 1. Source node — minimal pass-through, marked as source
		PipelineNode sourceNode = new FilesystemSourceNode();

		// 2. Size filter — accept files > 1 KB
		PipelineNode sizeFilter = new SizeFilterNode("size-filter", 1024);

		// 3. Hash node — actual SHA512Node
		SHA512Node sha512Cortex = new SHA512Node(null, opts, new HashNodeOptions());
		PipelineNode hashNode = createAdapterNode("sha512", sha512Cortex,
				NodeMode.PARALLEL, true, 2);

		// 4. Tika node — actual TikaNode
		TikaNode tikaCortex = new TikaNode(null, opts, new TikaNodeOptions());
		PipelineNode tikaNode = createAdapterNode("tika", tikaCortex,
				NodeMode.PARALLEL, true, 2);

		// Wire the DAG
		sourceNode.connectTo(sizeFilter);
		sizeFilter.connectTo(hashNode, FilterBranch.PASS);
		sizeFilter.connectTo(tikaNode, FilterBranch.PASS);

		Pipeline pipeline = DefaultPipeline.builder("integration-test")
				.description("Integration test with real nodes")
				.source(sourceNode)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

		log.info("Pipeline result: {}", result);
		for (Map.Entry<String, NodeResult> e : result.getNodeResults().entrySet()) {
			log.info("  {} -> {} {}", e.getKey(), e.getValue().getState(),
					e.getValue().getOutput() != null ? e.getValue().getOutput() : "");
		}

		assertTrue(result.isSuccess(), "Pipeline should succeed");
		assertEquals(4, result.getNodeResults().size());

		// Source completed
		assertEquals(NodeState.COMPLETED, result.getNodeResults().get("fs-source").getState());

		// Filter passed
		NodeResult filterResult = result.getNodeResults().get("size-filter");
		assertEquals(NodeState.COMPLETED, filterResult.getState());
		assertTrue((Boolean) filterResult.getOutput(PipelineNode.FILTER_PASSED),
				"Video file should pass the size filter");

		// Hash completed and produced SHA-512 output
		NodeResult hashResult = result.getNodeResults().get("sha512");
		assertEquals(NodeState.COMPLETED, hashResult.getState());
		assertNotNull(hashResult.getOutput("sha512"), "SHA-512 hash should be in outputs");
		assertNotNull(media.getSHA512(), "Media should have SHA-512 set");

		// Tika completed and produced flags output
		NodeResult tikaResult = result.getNodeResults().get("tika");
		assertEquals(NodeState.COMPLETED, tikaResult.getState());
		assertEquals("DONE", tikaResult.getOutput("tika_flags"));
	}

	/**
	 * Process a file that is too small and verify the filter rejects,
	 * causing downstream hash and tika nodes to be skipped.
	 */
	@Test
	void testSizeFilterRejectsSmallFile() throws IOException {
		CortexOptions opts = options();
		LoomMedia media = mediaImage1();

		PipelineNode sourceNode = new FilesystemSourceNode();

		// Set a very high threshold so the file is rejected
		long highThreshold = 500 * 1024 * 1024; // 500 MB
		PipelineNode sizeFilter = new SizeFilterNode("size-filter", highThreshold);

		SHA512Node sha512Cortex = new SHA512Node(null, opts, new HashNodeOptions());
		PipelineNode hashNode = createAdapterNode("sha512", sha512Cortex,
				NodeMode.PARALLEL, true, 2);

		TikaNode tikaCortex = new TikaNode(null, opts, new TikaNodeOptions());
		PipelineNode tikaNode = createAdapterNode("tika", tikaCortex,
				NodeMode.PARALLEL, true, 2);

		// Wire the DAG
		sourceNode.connectTo(sizeFilter);
		sizeFilter.connectTo(hashNode, FilterBranch.PASS);
		sizeFilter.connectTo(tikaNode, FilterBranch.PASS);

		Pipeline pipeline = DefaultPipeline.builder("reject-test")
				.source(sourceNode)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

		assertTrue(result.isSuccess());

		// Filter rejected
		NodeResult filterResult = result.getNodeResults().get("size-filter");
		assertEquals(NodeState.COMPLETED, filterResult.getState());
		assertEquals(false, filterResult.getOutput(PipelineNode.FILTER_PASSED));

		// Downstream nodes should be skipped due to filter branch mismatch
		assertEquals(NodeState.SKIPPED, result.getNodeResults().get("sha512").getState());
		assertEquals(NodeState.SKIPPED, result.getNodeResults().get("tika").getState());
	}

	/**
	 * Process multiple media items through the same pipeline and verify
	 * each completes independently.
	 */
	@Test
	void testBatchProcessing() throws IOException {
		CortexOptions opts = options();

		PipelineNode sourceNode = new FilesystemSourceNode();
		PipelineNode sizeFilter = new SizeFilterNode("size-filter", 1);

		SHA512Node sha512Cortex = new SHA512Node(null, opts, new HashNodeOptions());
		PipelineNode hashNode = createAdapterNode("sha512", sha512Cortex,
				NodeMode.PARALLEL, true, 2);

		TikaNode tikaCortex = new TikaNode(null, opts, new TikaNodeOptions());
		PipelineNode tikaNode = createAdapterNode("tika", tikaCortex,
				NodeMode.PARALLEL, true, 2);

		// Wire the DAG
		sourceNode.connectTo(sizeFilter);
		sizeFilter.connectTo(hashNode, FilterBranch.PASS);
		sizeFilter.connectTo(tikaNode, FilterBranch.PASS);

		Pipeline pipeline = DefaultPipeline.builder("batch-test")
				.source(sourceNode)
				.build();

		List<LoomMedia> mediaItems = List.of(
				media(video1()),
				media(image1()),
				media(audio1()));

		List<PipelineResult> results = executor.execute(pipeline, Flowable.fromIterable(mediaItems))
				.toList().blockingGet();

		assertEquals(3, results.size());
		for (PipelineResult r : results) {
			assertTrue(r.isSuccess(), "Each media item should succeed: " + r);
			assertEquals(NodeState.COMPLETED, r.getNodeResults().get("sha512").getState());
			log.info("Batch item: {}", r);
		}
	}

	// --- Helper: Adapter that wraps a CortexNode but controls isSource and id ---

	private PipelineNode createAdapterNode(String id,
			io.metaloom.cortex.api.node.FilesystemNode<?, ?> cortexNode,
			NodeMode mode, boolean blocking, int concurrency) {
		return new AbstractPipelineNode(id, cortexNode.name(), mode, blocking, concurrency) {
			@Override
			public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
				long start = System.currentTimeMillis();
				try {
					io.metaloom.cortex.api.node.NodeResult cortexResult = cortexNode.process(media);
					long elapsed = System.currentTimeMillis() - start;
					return switch (cortexResult.getState()) {
						case SUCCESS -> NodeResult.success(id(), elapsed, cortexResult.getOutputs());
						case SKIPPED -> NodeResult.skipped(id(), "Cortex node skipped");
						case FAILED -> NodeResult.failed(id(), elapsed, "Cortex node failed");
					};
				} catch (Exception e) {
					long elapsed = System.currentTimeMillis() - start;
					log.error("Error in cortex node {}: {}", id, e.getMessage(), e);
					return NodeResult.failed(id(), elapsed, e.getMessage());
				}
			}
		};
	}

	// --- Inner classes ---

	/**
	 * Minimal source node that passes through (no-op). Marks the entry point of the pipeline.
	 */
	static class FilesystemSourceNode extends AbstractPipelineNode {

		FilesystemSourceNode() {
			super("fs-source", "Filesystem Source", NodeMode.SEQUENTIAL, true, 1);
			setSource(true);
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			// Source just acknowledges the media item
			return NodeResult.success(id(), 0, Map.of("path", media.absolutePath()));
		}
	}

	/**
	 * Filter node that rejects files smaller than the configured threshold.
	 */
	static class SizeFilterNode extends AbstractFilterNode {

		private final long minSizeBytes;

		SizeFilterNode(String id, long minSizeBytes) {
			super(id, "Size Filter (min " + minSizeBytes + " bytes)");
			this.minSizeBytes = minSizeBytes;
		}

		@Override
		protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			try {
				return media.size() >= minSizeBytes;
			} catch (IOException e) {
				return false;
			}
		}

		@Override
		protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			try {
				return "File size " + media.size() + " < " + minSizeBytes;
			} catch (IOException e) {
				return "Could not read file size";
			}
		}
	}
}
