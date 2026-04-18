package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractPipelineNodeTest;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.MD5;

/**
 * Pipeline integration test for {@link MD5Node}.
 *
 * <p>Demonstrates the recommended template for testing a Cortex node inside a pipeline:
 * <ol>
 *   <li><b>Setup</b> — create temp file and configure the node with mocked options</li>
 *   <li><b>Execution</b> — wrap the node in a {@link CortexNodeAdapter}, chain to
 *       an {@link AssetSourceNode}, build a pipeline, and execute</li>
 *   <li><b>Result handling</b> — assert overall success, node state, and output values</li>
 *   <li><b>Events</b> — verify that completion and tracking events were dispatched</li>
 *   <li><b>Chaining</b> — test that output flows to downstream nodes</li>
 *   <li><b>Settings</b> — test disabled/dry-run/offline scenarios</li>
 * </ol>
 */
public class MD5NodePipelineTest extends AbstractPipelineNodeTest {

	@TempDir
	File tempDir;

	private File testFile;
	private String expectedMd5;
	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		testFile = new File(tempDir, "test-asset.bin");
		Files.write(testFile.toPath(), "pipeline-test-content".getBytes());
		expectedMd5 = HashUtils.computeMD5(testFile).toString();
		media = StubLoomMedia.ofFile(testFile);
	}

	// -- Setup helpers --

	private MD5Node createNode() {
		return createNode(true);
	}

	private MD5Node createNode(boolean md5Enabled) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isMD5()).thenReturn(md5Enabled);
		when(options.isEnabled()).thenReturn(true);
		CortexOptions cortexOptions = new CortexOptions();
		return new MD5Node(null, cortexOptions, options);
	}

	private CortexNodeAdapter adapt(MD5Node node) {
		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testMD5Computation() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("asset-source")
				.hasCompletedNode("md5")
				.hasNodeOutput("md5", "md5", expectedMd5);
	}

	@Test
	void testNodeOutput() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result).node("md5")
				.isCompleted()
				.hasOutput("md5", expectedMd5)
				.hasOutputCount(1);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		NodeCompletionEvent sourceEvent = assertCompletionEvent("asset-source");
		assertThat(sourceEvent.getResult().getState()).isEqualTo(NodeState.COMPLETED);

		NodeCompletionEvent md5Event = assertCompletionEvent("md5");
		assertThat(md5Event.getResult().getState()).isEqualTo(NodeState.COMPLETED);
		assertThat(md5Event.getMedia()).isSameAs(media);
	}

	@Test
	void testTrackingEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		assertTrackingEvent("md5", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("md5", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining — downstream node reads MD5 output
	// ========================================================================

	@Test
	void testOutputChaining() {
		CortexNodeAdapter md5Adapter = adapt(createNode());

		// Downstream node that reads the MD5 value from upstream
		List<String> receivedMd5 = new CopyOnWriteArrayList<>();
		AbstractPipelineNode downstream = new AbstractPipelineNode(
				"consumer", "Consumer", NodeMode.SEQUENTIAL, true, 1) {
			@Override
			public NodeResult process(io.metaloom.cortex.api.media.LoomMedia media,
					Map<String, NodeResult> upstreamResults) {
				NodeResult md5Result = upstreamResults.get("md5");
				String md5 = md5Result != null ? md5Result.getOutput("md5") : null;
				receivedMd5.add(md5);
				return NodeResult.success(id(), 0, Map.of("received_md5", md5 != null ? md5 : ""));
			}
		};

		// Build pipeline: source → md5 → consumer
		AssetSourceNode source = new AssetSourceNode(media);
		source.connectTo(md5Adapter);
		md5Adapter.connectTo(downstream);

		Pipeline pipeline = DefaultPipeline.builder("chaining-test")
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(result).hasNodeOutput("consumer", "received_md5", expectedMd5);
		assertThat(receivedMd5).containsExactly(expectedMd5);
	}

	// ========================================================================
	// 4. Settings — disabled, dry-run
	// ========================================================================

	@Test
	void testDisabledNode() {
		MD5Node node = createNode(false);
		CortexNodeAdapter adapter = adapt(node);

		PipelineResult result = execute(media, adapter);

		// The node skips because isMD5()=false, adapter maps that to SKIPPED
		assertThat(result).isSuccess();
		assertThat(result).node("md5").isSkipped();
	}

	@Test
	void testDryRunPipeline() {
		CortexNodeAdapter adapter = adapt(createNode());

		AssetSourceNode source = new AssetSourceNode(media);
		source.connectTo(adapter);

		Pipeline pipeline = DefaultPipeline.builder("dryrun-test")
				.dryRun(true)
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

		assertThat(result).isDryRun();
		assertThat(result).node("asset-source").isSkipped();
		assertThat(result).node("md5").isSkipped();
	}

	@Test
	void testMissingFileFailsGracefully() {
		File missing = new File(tempDir, "does-not-exist.bin");
		StubLoomMedia missingMedia = StubLoomMedia.ofFile(missing);
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(missingMedia, adapter);

		// MD5Node checks media.exists() and returns failure
		assertThat(result).isFailed();
		assertThat(result).node("md5").isFailed();
	}
}
