package io.metaloom.cortex.node.tts;

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
 * Pipeline integration test for {@link TtsNode}. The synthesis (an HTTP call to the FastAPI sidecar) and the upstream-text gate are stubbed, so this
 * focuses on the pipeline adapter integration, event dispatch, and output chaining. The text-in / audio-out logic is covered by {@link TtsNodeTest}.
 */
class TtsNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_PATH = "/var/meta/tts_bin/ab/cd/hash.wav";

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		TtsNodeOptions options = mock(TtsNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		TtsClient ttsClient = mock(TtsClient.class);

		TtsNode node = spy(new TtsNode(null, new CortexOptions(), options, ttsClient));

		if (enabled) {
			// Bypass the upstream-text gate and the real HTTP call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(TtsNode.OUTPUT_TTS_FLAG, "DONE");
				ctx.output(TtsNode.OUTPUT_TTS_PATH, FAKE_PATH);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testTtsOnVideo() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("tts")
			.hasNodeOutput("tts", "tts_path", FAKE_PATH);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("tts");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		assertTrackingEvent("tts", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("tts", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter ttsAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", "tts", "tts_path");

		PipelineResult result = execute(media, ttsAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_PATH);
	}

	@Test
	void testDisabledNode() throws Exception {
		CortexNodeAdapter adapter = createAdapter(false);

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("tts").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("tts").isSkipped();
	}
}
