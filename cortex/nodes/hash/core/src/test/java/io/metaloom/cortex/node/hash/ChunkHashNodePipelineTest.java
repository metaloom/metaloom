package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractPipelineNodeTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.HashUtils;

/**
 * Pipeline integration test for {@link ChunkHashNode}.
 */
class ChunkHashNodePipelineTest extends AbstractPipelineNodeTest {

	@TempDir
	File tempDir;

	private String expectedChunkHash;
	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		media = StubLoomMedia.ofBytes(tempDir, "test-asset.bin", "chunkhash-pipeline-test-content");
		expectedChunkHash = HashUtils.computeChunkHash(media.file()).toString();
	}

	private ChunkHashNode createNode() {
		return createNode(true);
	}

	private ChunkHashNode createNode(boolean chunkHashEnabled) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isChunkHash()).thenReturn(chunkHashEnabled);
		when(options.isEnabled()).thenReturn(true);
		return new ChunkHashNode(null, new CortexOptions(), options);
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
		CapturingNode consumer = new CapturingNode("consumer", "chunk-hash", "chunk_hash");

		PipelineResult result = execute(media, chunkAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(expectedChunkHash);
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
