package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
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
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;

/**
 * Pipeline integration test for {@link FacedetectNode}.
 *
 * <p>The actual face detection requires native InspireFace and Video4j
 * libraries, so the {@code compute()} method is stubbed. This tests the
 * pipeline adapter integration, event dispatch, output chaining, and
 * media type filtering.</p>
 */
class FacedetectNodePipelineTest extends AbstractPipelineNodeTest {

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

	private CortexNodeAdapter createAdapter(int faceCount) throws Exception {
		FacedetectNodeOptions options = mock(FacedetectNodeOptions.class);
		when(options.isEnabled()).thenReturn(true);

		InspireFacedetector inspireface = mock(InspireFacedetector.class);
		VideoFaceScanner videoScanner = mock(VideoFaceScanner.class);

		FacedetectNode node = spy(new FacedetectNode(null, new CortexOptions(), options, inspireface, videoScanner));

		// Stub the compute method to avoid native calls
		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(FacedetectNode.OUTPUT_FACE_COUNT, faceCount);
			ctx.output(FacedetectNode.OUTPUT_FACEDETECT_FLAG, faceCount > 0 ? "SUCCESS" : "NONE");
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		return adapt(node);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testFaceDetectionOnVideo() throws Exception {
		CortexNodeAdapter adapter = createAdapter(3);

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("facedetect")
				.hasNodeOutput("facedetect", "face_count", 3)
				.hasNodeOutput("facedetect", "facedetect_flag", "SUCCESS");
	}

	@Test
	void testFaceDetectionOnImage() throws Exception {
		CortexNodeAdapter adapter = createAdapter(1);

		PipelineResult result = execute(imageMedia, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("facedetect")
				.hasNodeOutput("facedetect", "face_count", 1);
	}

	@Test
	void testNoFacesDetected() throws Exception {
		CortexNodeAdapter adapter = createAdapter(0);

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result).node("facedetect")
				.isCompleted()
				.hasOutput("face_count", 0)
				.hasOutput("facedetect_flag", "NONE");
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter(2);

		execute(videoMedia, adapter);

		NodeCompletionEvent event = assertCompletionEvent("facedetect");
		assertThat(event.getResult().getState()).isEqualTo(NodeState.COMPLETED);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter(2);

		execute(videoMedia, adapter);

		assertTrackingEvent("facedetect", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("facedetect", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter faceAdapter = createAdapter(5);
		CapturingNode consumer = new CapturingNode("consumer", "facedetect", "face_count");

		PipelineResult result = execute(videoMedia, faceAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(5);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testAudioMediaSkipped() throws Exception {
		StubLoomMedia audioMedia = new StubLoomMedia(testFile.getAbsolutePath(), false, false, true, false);
		CortexNodeAdapter adapter = createAdapter(0);

		PipelineResult result = execute(audioMedia, adapter);

		// Facedetect only processes video and image
		assertThat(result).isSuccess();
		assertThat(result).node("facedetect").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter(2);

		AssetSourceNode source = new AssetSourceNode(videoMedia);
		source.connectTo(adapter);

		Pipeline pipeline = DefaultPipeline.builder("dryrun-test")
				.dryRun(true)
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, videoMedia);

		assertThat(result).isDryRun();
		assertThat(result).node("facedetect").isSkipped();
	}
}
