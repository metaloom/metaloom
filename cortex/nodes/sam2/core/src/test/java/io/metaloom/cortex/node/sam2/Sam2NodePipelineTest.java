package io.metaloom.cortex.node.sam2;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.sam2.video.Sam2FrameSampler;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * Pipeline integration test for {@link Sam2Node}. The inference (an HTTP call to the FastAPI sidecar)
 * is stubbed, so this focuses on the pipeline adapter integration, event dispatch, and output
 * chaining — including the {@code MANY} masks port, which is what a downstream {@code s3-sink} is
 * wired to.
 */
class Sam2NodePipelineTest extends AbstractNodeChainTest {

	private static final String MASK_A = "/var/meta/sam2_bin/ab/cd/hash-0123456789ab/mask-0000.png";
	private static final String MASK_B = "/var/meta/sam2_bin/ab/cd/hash-0123456789ab/mask-0001.png";
	private static final String OVERLAY = "/var/meta/sam2_bin/ab/cd/hash-0123456789ab/overlay.png";
	private static final String MANIFEST = "{\"model\":\"facebook/sam2.1-hiera-small\",\"mode\":\"AUTOMATIC\","
		+ "\"width\":1024,\"height\":683,\"imageWidth\":4000,\"imageHeight\":2667,\"masks\":[]}";

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "asset.png", "fake-image");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, true, false, false);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		Sam2NodeOptions options = mock(Sam2NodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		Sam2Client client = mock(Sam2Client.class);

		Sam2Node node = spy(new Sam2Node(null, new CortexOptions(), options, client, new Sam2FrameSampler()));

		if (enabled) {
			// Bypass the media-type gate, the native init and the real HTTP call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(Sam2Node.OUT_FLAG, Sam2Node.FLAG_SUCCESS);
				ctx.output(Sam2Node.OUT_MASK_COUNT, 2L);
				ctx.output(Sam2Node.OUT_SEGMENTS, MANIFEST);
				ctx.output(Sam2Node.OUT_OVERLAY, OVERLAY);
				ctx.outputElement(Sam2Node.OUT_MASKS, MASK_A);
				ctx.outputElement(Sam2Node.OUT_MASKS, MASK_B);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testSam2OnImage() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("sam2")
			.hasNodeOutput("sam2", Sam2Node.OUT_SEGMENTS, MANIFEST)
			.hasNodeOutput("sam2", Sam2Node.OUT_OVERLAY, OVERLAY)
			.hasNodeOutput("sam2", Sam2Node.OUT_MASK_COUNT, 2L);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("sam2");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("sam2", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("sam2", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testTheMasksPortChainsIndependentlyOfTheOverlay() throws Exception {
		// The wiring s3-sink uses. Both ports carry artifact/image paths into the same local
		// directory, so a consumer reaching for the masks must not be handed the overlay - the
		// difference between archiving the cut-outs and archiving one picture of them.
		CapturingNode consumer = new CapturingNode("consumer", Sam2Node.OUT_MASKS);

		PipelineResult result = execute(media, createAdapter(), consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		// CapturingNode reads one value per run (ctx.input, not ctx.inputs), so this pins routing
		// rather than element count; the full sequence is asserted in Sam2NodeTest.
		assertThat(consumer.capturedValues()).containsExactly(MASK_A);
	}

	@Test
	void testManifestReachesADownstreamConsumer() throws Exception {
		CapturingNode consumer = new CapturingNode("consumer", Sam2Node.OUT_SEGMENTS);

		PipelineResult result = execute(media, createAdapter(), consumer);

		assertThat(result).isSuccess();
		assertThat(consumer.capturedValues()).containsExactly(MANIFEST);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("sam2").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("sam2").isSkipped();
	}
}
