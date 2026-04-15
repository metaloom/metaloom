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
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractPipelineNodeTest;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.HashUtils;

/**
 * Pipeline integration test for {@link SHA512Node}.
 */
class SHA512NodePipelineTest extends AbstractPipelineNodeTest {

	@TempDir
	File tempDir;

	private File testFile;
	private String expectedSha512;
	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		testFile = new File(tempDir, "test-asset.bin");
		Files.write(testFile.toPath(), "sha512-pipeline-test-content".getBytes());
		expectedSha512 = HashUtils.computeSHA512(testFile).toString();
		media = StubLoomMedia.ofFile(testFile);
	}

	private SHA512Node createNode() {
		return createNode(true);
	}

	private SHA512Node createNode(boolean sha512Enabled) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isSHA512()).thenReturn(sha512Enabled);
		when(options.isEnabled()).thenReturn(true);
		CortexOptions cortexOptions = new CortexOptions();
		return new SHA512Node(null, cortexOptions, options);
	}

	private CortexNodeAdapter adapt(SHA512Node node) {
		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testSHA512Computation() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("sha512")
				.hasNodeOutput("sha512", "sha512", expectedSha512);
	}

	@Test
	void testNodeOutput() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result).node("sha512")
				.isCompleted()
				.hasOutput("sha512", expectedSha512)
				.hasOutputCount(1);
	}

	@Test
	void testSHA512SetsMediaHash() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		// SHA512Node sets the SHA512 on the media itself
		assertThat(media.hasSHA512()).isTrue();
		assertThat(media.getSHA512().toString()).isEqualTo(expectedSha512);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("sha512");
		assertThat(event.getResult().getState()).isEqualTo(NodeState.COMPLETED);
		assertThat(event.getMedia()).isSameAs(media);
	}

	@Test
	void testTrackingEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		assertTrackingEvent("sha512", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("sha512", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() {
		CortexNodeAdapter sha512Adapter = adapt(createNode());

		List<String> receivedHash = new CopyOnWriteArrayList<>();
		AbstractPipelineNode downstream = new AbstractPipelineNode(
				"consumer", "Consumer", NodeMode.SEQUENTIAL, true, 1) {
			@Override
			public NodeResult process(io.metaloom.cortex.api.media.LoomMedia media,
					Map<String, NodeResult> upstreamResults) {
				NodeResult sha512Result = upstreamResults.get("sha512");
				String hash = sha512Result != null ? sha512Result.getOutput("sha512") : null;
				receivedHash.add(hash);
				return NodeResult.success(id(), 0, Map.of("received_hash", hash != null ? hash : ""));
			}
		};

		AssetSourceNode source = new AssetSourceNode(media);
		source.connectTo(sha512Adapter);
		sha512Adapter.connectTo(downstream);

		Pipeline pipeline = DefaultPipeline.builder("chaining-test")
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(receivedHash).containsExactly(expectedSha512);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testDisabledNode() {
		CortexNodeAdapter adapter = adapt(createNode(false));

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("sha512").isSkipped();
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
		assertThat(result).node("sha512").isSkipped();
	}

	@Test
	void testMissingFileFailsGracefully() {
		File missing = new File(tempDir, "does-not-exist.bin");
		StubLoomMedia missingMedia = StubLoomMedia.ofFile(missing);
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(missingMedia, adapter);

		assertThat(result).isFailed();
		assertThat(result).node("sha512").isFailed();
	}
}
