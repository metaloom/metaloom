package io.metaloom.cortex.node.watermark;

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
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * Pipeline integration test for {@link WatermarkNode}: the adapter integration, event dispatch and artifact chaining into a downstream consumer (which is
 * how a real graph reaches {@code s3-sink}). The compositing itself is stubbed - {@link WatermarkNodeTest} covers the pixels.
 */
class WatermarkNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_PATH = "/var/meta/watermark_bin/ab/cd/hash-0123456789ab.png";

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
		WatermarkNodeOptions options = mock(WatermarkNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		WatermarkNode node = spy(new WatermarkNode(null, new CortexOptions(), options));

		if (enabled) {
			// Bypass the media-type gate and the real compositing.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(WatermarkNode.OUT_FLAG, "DONE");
				ctx.output(WatermarkNode.OUT_IMAGE, FAKE_PATH);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testWatermarkOnImage() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("watermark")
			.hasNodeOutput("watermark", WatermarkNode.OUT_IMAGE, FAKE_PATH);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("watermark");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("watermark", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("watermark", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testArtifactChainsToADownstreamConsumer() throws Exception {
		// This is the shape that matters in production: the marked file is only durable once a sink picks it up off the image port.
		CortexNodeAdapter watermark = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", WatermarkNode.OUT_IMAGE);

		PipelineResult result = execute(media, watermark, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_PATH);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("watermark").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("watermark").isSkipped();
	}
}
