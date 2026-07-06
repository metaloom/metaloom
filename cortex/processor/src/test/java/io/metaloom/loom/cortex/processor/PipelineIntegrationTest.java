package io.metaloom.loom.cortex.processor;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.cortex.node.tika.TikaNode;
import io.metaloom.cortex.node.tika.TikaNodeOptions;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.executor.ReactivePipelineExecutor;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.core.node.filter.AbstractFilterNode;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Pipeline integration test that uses actual Cortex node implementations
 * (SHA512Node, TikaNode) wired into a DAG pipeline with an asset source
 * and a size filter.
 *
 * <pre>
 * source (asset-source) → filter (size-filter) → hash (sha512) + tika (tika)
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

	private static CortexNodeAdapter adapt(io.metaloom.cortex.api.node.FilesystemNode<?, ?> node) {
		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 2);
	}

	/**
	 * Build a pipeline:
	 * <pre>
	 *   asset-source -> size-filter --(PASS)--> sha512 (parallel)
	 *                                --(PASS)--> tika   (parallel)
	 * </pre>
	 * Process a video file that passes the size filter and verify all nodes complete.
	 */
	@Test
	void testPipelineWithRealNodesOnVideo() throws IOException {
		CortexOptions opts = options();
		LoomMedia media = media(video1());

		PipelineNode source = new AssetSourceNode(media);
		PipelineNode sizeFilter = new SizeFilterNode("size-filter", 1024);
		CortexNodeAdapter hashNode = adapt(new SHA512Node(null, opts, new HashNodeOptions()));
		CortexNodeAdapter tikaNode = adapt(new TikaNode(null, opts, new TikaNodeOptions()));

		source.connectTo(sizeFilter);
		sizeFilter.connectTo(hashNode, FilterBranch.PASS);
		sizeFilter.connectTo(tikaNode, FilterBranch.PASS);

		Pipeline pipeline = DefaultPipeline.builder("integration-test")
				.description("Integration test with real nodes")
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, media);
		log.info("Pipeline result: {}", result);

		assertThat(result)
				.isSuccess()
				.hasNodeCount(4)
				.hasCompletedNode("asset-source")
				.hasCompletedNode("size-filter")
				.hasNodeOutput("size-filter", PipelineNode.FILTER_PASSED, true)
				.hasCompletedNode("sha512")
				.hasCompletedNode("tika")
				.hasNodeOutput("tika", "tika_flags", "DONE");

		assertThat(result).node("sha512").hasOutput("sha512");
		assertThat(media.getSHA512()).as("Media should have SHA-512 set").isNotNull();
	}

	/**
	 * Process a file that is too small and verify the filter rejects,
	 * causing downstream hash and tika nodes to be skipped.
	 */
	@Test
	void testSizeFilterRejectsSmallFile() throws IOException {
		CortexOptions opts = options();
		LoomMedia media = mediaImage1();

		PipelineNode source = new AssetSourceNode(media);
		// Set a very high threshold so the file is rejected
		PipelineNode sizeFilter = new SizeFilterNode("size-filter", 500 * 1024 * 1024L);
		CortexNodeAdapter hashNode = adapt(new SHA512Node(null, opts, new HashNodeOptions()));
		CortexNodeAdapter tikaNode = adapt(new TikaNode(null, opts, new TikaNodeOptions()));

		source.connectTo(sizeFilter);
		sizeFilter.connectTo(hashNode, FilterBranch.PASS);
		sizeFilter.connectTo(tikaNode, FilterBranch.PASS);

		Pipeline pipeline = DefaultPipeline.builder("reject-test").source(source).build();
		PipelineResult result = executor.execute(pipeline, media);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("size-filter")
				.hasNodeOutput("size-filter", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node("sha512").isSkipped();
		assertThat(result).node("tika").isSkipped();
	}

	/**
	 * Process multiple media items through the same pipeline and verify
	 * each completes independently.
	 */
	@Test
	void testBatchProcessing() throws IOException {
		CortexOptions opts = options();

		List<LoomMedia> mediaItems = List.of(
				media(video1()),
				media(image1()),
				media(audio1()));

		PipelineNode source = new AssetSourceNode(mediaItems.get(0));
		PipelineNode sizeFilter = new SizeFilterNode("size-filter", 1);
		CortexNodeAdapter hashNode = adapt(new SHA512Node(null, opts, new HashNodeOptions()));
		CortexNodeAdapter tikaNode = adapt(new TikaNode(null, opts, new TikaNodeOptions()));

		source.connectTo(sizeFilter);
		sizeFilter.connectTo(hashNode, FilterBranch.PASS);
		sizeFilter.connectTo(tikaNode, FilterBranch.PASS);

		Pipeline pipeline = DefaultPipeline.builder("batch-test").source(source).build();

		List<PipelineResult> results = executor.execute(pipeline, Flowable.fromIterable(mediaItems))
				.toList().blockingGet();

		assertThat(results).hasSize(3);
		for (PipelineResult r : results) {
			assertThat(r).isSuccess().hasCompletedNode("sha512");
			log.info("Batch item: {}", r);
		}
	}

	// --- Inner classes ---

	/**
	 * Filter node that rejects files smaller than the configured threshold.
	 * Kept local because no production SizeFilterNode exists yet.
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
