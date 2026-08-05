package io.metaloom.cortex.node.objectdetect;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.objectdetect.video.VideoObjectScanner;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * Pipeline adapter integration for {@link ObjectDetectNode}.
 *
 * <p>
 * The real detection needs native YOLO and Video4j libraries, so {@code compute()} is stubbed. What
 * is under test here is everything around it: event dispatch, output chaining, media-type filtering
 * and the dry-run skip.
 * </p>
 */
class ObjectDetectNodePipelineTest extends AbstractNodeChainTest {

	@TempDir
	File tempDir;

	private File testFile;
	private StubLoomMedia videoMedia;
	private StubLoomMedia imageMedia;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "test-video.mp4", "fake-video-content");
		testFile = backing.file();
		videoMedia = new StubLoomMedia(testFile.getAbsolutePath(), true, false, false, false);
		imageMedia = new StubLoomMedia(testFile.getAbsolutePath(), false, true, false, false);
	}

	private CortexNodeAdapter createAdapter(int objectCount) throws Exception {
		ObjectDetectNodeOptions options = mock(ObjectDetectNodeOptions.class);
		when(options.isEnabled()).thenReturn(true);

		ObjectDetector detector = mock(ObjectDetector.class);
		VideoObjectScanner scanner = mock(VideoObjectScanner.class);

		ObjectDetectNode node = spy(new ObjectDetectNode(null, new CortexOptions(), options, detector, scanner));

		// Stub compute to avoid the native calls.
		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(ObjectDetectNode.OUT_OBJECT_COUNT, (long) objectCount);
			ctx.output(ObjectDetectNode.OUT_FLAG, objectCount > 0 ? "SUCCESS" : "NONE");
			// One element per object, matching what the real compute() emits: the element count is what
			// the engine reads to size the downstream per-object fan-out.
			for (int i = 0; i < objectCount; i++) {
				ctx.outputElement(ObjectDetectNode.OUT_DETECTIONS, "{\"index\":" + i + ",\"type\":\"object\"}");
			}
			if (objectCount > 0) {
				ctx.outputElement(ObjectDetectNode.OUT_LABELS, "person");
			}
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		return adapt(node);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testObjectDetectionOnVideo() throws Exception {
		CortexNodeAdapter adapter = createAdapter(3);

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("objectdetect")
			.hasNodeOutput("objectdetect", ObjectDetectNode.OUT_OBJECT_COUNT, 3L)
			.hasNodeOutput("objectdetect", ObjectDetectNode.OUT_FLAG, "SUCCESS");
	}

	@Test
	void testObjectDetectionOnImage() throws Exception {
		CortexNodeAdapter adapter = createAdapter(1);

		PipelineResult result = execute(imageMedia, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("objectdetect")
			.hasNodeOutput("objectdetect", ObjectDetectNode.OUT_OBJECT_COUNT, 1L);
	}

	@Test
	void testNothingDetected() throws Exception {
		CortexNodeAdapter adapter = createAdapter(0);

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result).node("objectdetect")
			.isCompleted()
			.hasOutput(ObjectDetectNode.OUT_OBJECT_COUNT, 0L)
			.hasOutput(ObjectDetectNode.OUT_FLAG, "NONE");
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter(2);

		execute(videoMedia, adapter);

		NodeCompletionEvent event = assertCompletionEvent("objectdetect");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter(2);

		execute(videoMedia, adapter);

		assertTrackingEvent("objectdetect", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("objectdetect", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter detectAdapter = createAdapter(5);
		CapturingNode consumer = new CapturingNode("consumer", ObjectDetectNode.OUT_OBJECT_COUNT);

		PipelineResult result = execute(videoMedia, detectAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		// scalar/integer arrives as Long on both sides of the wire.
		assertThat(consumer.capturedValues()).containsExactly(5L);
	}

	@Test
	void testTheLabelsPortChainsIndependentlyOfTheDetections() throws Exception {
		CortexNodeAdapter detectAdapter = createAdapter(5);
		CapturingNode consumer = new CapturingNode("consumer", ObjectDetectNode.OUT_LABELS);

		PipelineResult result = execute(videoMedia, detectAdapter, consumer);

		// Two MANY outputs of different lengths - five detections, one distinct class. A consumer wired
		// to labels must see the labels, not the detection sequence; this is the wiring the tag node uses.
		assertThat(result).isSuccess();
		assertThat(consumer.capturedValues()).isEqualTo(List.of("person"));
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testAudioMediaSkipped() throws Exception {
		StubLoomMedia audioMedia = new StubLoomMedia(testFile.getAbsolutePath(), false, false, true, false);
		CortexNodeAdapter adapter = createAdapter(0);

		PipelineResult result = execute(audioMedia, adapter);

		// objectdetect only processes video and image.
		assertThat(result).isSuccess();
		assertThat(result).node("objectdetect").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter(2);

		PipelineResult result = executeDryRun(videoMedia, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("objectdetect").isSkipped();
	}
}
