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
 * Pipeline integration test for {@link ChunkHashNode}.
 */
class ChunkHashNodePipelineTest extends AbstractPipelineNodeTest {

	@TempDir
	File tempDir;

	private File testFile;
	private String expectedChunkHash;
	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		testFile = new File(tempDir, "test-asset.bin");
		Files.write(testFile.toPath(), "chunkhash-pipeline-test-content".getBytes());
		expectedChunkHash = HashUtils.computeChunkHash(testFile).toString();
		media = StubLoomMedia.ofFile(testFile);
	}

	private ChunkHashNode createNode() {
		return createNode(true);
	}

	private ChunkHashNode createNode(boolean chunkHashEnabled) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isChunkHash()).thenReturn(chunkHashEnabled);
		when(options.isEnabled()).thenReturn(true);
		CortexOptions cortexOptions = new CortexOptions();
		return new ChunkHashNode(null, cortexOptions, options);
	}

	private CortexNodeAdapter adapt(ChunkHashNode node) {
		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testChunkHashComputation() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("chunk-hash")
				.hasNodeOutput("chunk-hash", "chunk_hash", expectedChunkHash);
	}

	@Test
	void testNodeOutput() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result).node("chunk-hash")
				.isCompleted()
				.hasOutput("chunk_hash", expectedChunkHash)
				.hasOutputCount(1);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("chunk-hash");
		assertThat(event.getResult().getState()).isEqualTo(NodeState.COMPLETED);
		assertThat(event.getMedia()).isSameAs(media);
	}

	@Test
	void testTrackingEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		assertTrackingEvent("chunk-hash", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("chunk-hash", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() {
		CortexNodeAdapter chunkAdapter = adapt(createNode());

		List<String> receivedHash = new CopyOnWriteArrayList<>();
		AbstractPipelineNode downstream = new AbstractPipelineNode(
				"consumer", "Consumer", NodeMode.SEQUENTIAL, true, 1) {
			@Override
			public NodeResult process(io.metaloom.cortex.api.media.LoomMedia media,
					Map<String, NodeResult> upstreamResults) {
				NodeResult hashResult = upstreamResults.get("chunk-hash");
				String hash = hashResult != null ? hashResult.getOutput("chunk_hash") : null;
				receivedHash.add(hash);
				return NodeResult.success(id(), 0, Map.of("received_hash", hash != null ? hash : ""));
			}
		};

		AssetSourceNode source = new AssetSourceNode(media);
		source.connectTo(chunkAdapter);
		chunkAdapter.connectTo(downstream);

		Pipeline pipeline = DefaultPipeline.builder("chaining-test")
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(receivedHash).containsExactly(expectedChunkHash);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testDisabledNode() {
		CortexNodeAdapter adapter = adapt(createNode(false));

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("chunk-hash").isSkipped();
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
		assertThat(result).node("chunk-hash").isSkipped();
	}

	@Test
	void testMissingFileFailsGracefully() {
		File missing = new File(tempDir, "does-not-exist.bin");
		StubLoomMedia missingMedia = StubLoomMedia.ofFile(missing);
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(missingMedia, adapter);

		assertThat(result).isFailed();
		assertThat(result).node("chunk-hash").isFailed();
	}
}
