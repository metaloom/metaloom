package io.metaloom.cortex.node.imagegen;

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
 * Pipeline integration test for {@link ImageGenNode}. The generation (an HTTP call to the FastAPI sidecar) is stubbed, so this focuses on the pipeline
 * adapter integration, event dispatch, and output chaining. The prompt-in / image-out logic is covered by {@link ImageGenNodeTest}.
 */
class ImageGenNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_PATH = "/var/meta/imagegen_bin/ab/cd/hash.png";

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
		ImageGenNodeOptions options = mock(ImageGenNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		ImageGenClient client = mock(ImageGenClient.class);

		ImageGenNode node = spy(new ImageGenNode(null, new CortexOptions(), options, client));

		if (enabled) {
			// Bypass the media-type gate and the real HTTP call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(ImageGenNode.OUT_FLAG, "DONE");
				ctx.output(ImageGenNode.OUT_IMAGE, FAKE_PATH);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testImageGenOnImage() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("imagegen")
			.hasNodeOutput("imagegen", ImageGenNode.OUT_IMAGE, FAKE_PATH);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("imagegen");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		assertTrackingEvent("imagegen", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("imagegen", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter imageGenAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", ImageGenNode.OUT_IMAGE);

		PipelineResult result = execute(media, imageGenAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_PATH);
	}

	@Test
	void testDisabledNode() throws Exception {
		CortexNodeAdapter adapter = createAdapter(false);

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("imagegen").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("imagegen").isSkipped();
	}
}
