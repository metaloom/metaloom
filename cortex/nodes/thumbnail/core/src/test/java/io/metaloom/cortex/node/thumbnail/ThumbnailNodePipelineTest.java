package io.metaloom.cortex.node.thumbnail;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * Pipeline integration test for {@link ThumbnailNode}.
 *
 * <p>The actual thumbnail generation requires native Video4j libraries,
 * so the {@code compute()} method is stubbed. This tests the pipeline
 * adapter integration, event dispatch, output chaining, and media type
 * filtering (video only).</p>
 */
class ThumbnailNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_THUMBNAIL_PATH = "/tmp/thumbnails/test.thumb";

	@TempDir
	File tempDir;

	private File testFile;
	private StubLoomMedia videoMedia;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "test-video.mp4", "fake-video-content");
		testFile = backing.file();
		videoMedia = new StubLoomMedia(testFile.getAbsolutePath(), true, false, false, false);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setEnabled(true);

		CortexOptions cortexOptions = new CortexOptions();
		cortexOptions.setMetaPath(tempDir.toPath());
		ThumbnailNode node = spy(new ThumbnailNode(null, cortexOptions, options));

		// Stub the compute method to avoid native Video4j calls
		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(ThumbnailNode.OUTPUT_THUMBNAIL_FLAG, "DONE");
			ctx.output(ThumbnailNode.OUTPUT_THUMBNAIL_PATH, FAKE_THUMBNAIL_PATH);
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		return adapt(node);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testThumbnailGeneration() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("thumbnail")
				.hasNodeOutput("thumbnail", "thumbnail_flag", "DONE")
				.hasNodeOutput("thumbnail", "thumbnail_path", FAKE_THUMBNAIL_PATH);
	}

	@Test
	void testNodeOutput() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result).node("thumbnail")
				.isCompleted()
				.hasOutput("thumbnail_flag", "DONE")
				.hasOutput("thumbnail_path", FAKE_THUMBNAIL_PATH)
				.hasOutputCount(2);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(videoMedia, adapter);

		NodeCompletionEvent event = assertCompletionEvent("thumbnail");
		assertThat(event.getResult().getState()).isEqualTo(NodeState.COMPLETED);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(videoMedia, adapter);

		assertTrackingEvent("thumbnail", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("thumbnail", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter thumbAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", "thumbnail", "thumbnail_path");

		PipelineResult result = execute(videoMedia, thumbAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_THUMBNAIL_PATH);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testNonVideoMediaSkipped() throws Exception {
		StubLoomMedia audioMedia = new StubLoomMedia(testFile.getAbsolutePath(), false, false, true, false);
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(audioMedia, adapter);

		// Thumbnail only works on video
		assertThat(result).isSuccess();
		assertThat(result).node("thumbnail").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(videoMedia, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("thumbnail").isSkipped();
	}
}
