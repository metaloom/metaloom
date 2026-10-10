package io.metaloom.cortex.node.voiceprint;

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
 * Pipeline integration test for {@link VoiceprintNode}. The sidecar call is stubbed, so this covers the pipeline adapter integration, event
 * dispatch and output chaining. The analysis and persistence are covered by {@link VoiceprintNodeTest} and {@link VoiceprintNodePersistenceTest}.
 */
class VoiceprintNodePipelineTest extends AbstractNodeChainTest {

	private static final long FAKE_COUNT = 2L;

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "talk.wav", "fake-audio");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, true, false);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		VoiceprintNodeOptions options = mock(VoiceprintNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		VoiceprintClient voiceprintClient = mock(VoiceprintClient.class);

		VoiceprintNode node = spy(new VoiceprintNode(null, new CortexOptions(), options, voiceprintClient));

		if (enabled) {
			// Bypass the media gate and the real HTTP call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(VoiceprintNode.OUT_SPEAKER_COUNT, FAKE_COUNT);
				ctx.output(VoiceprintNode.OUT_RESULT, "{\"speakerCount\":2}");
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testVoiceprintOnAudio() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("voiceprint")
			.hasNodeOutput("voiceprint", VoiceprintNode.OUT_SPEAKER_COUNT, FAKE_COUNT);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("voiceprint");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		assertTrackingEvent("voiceprint", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("voiceprint", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter voiceprintAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", VoiceprintNode.OUT_SPEAKER_COUNT);

		PipelineResult result = execute(media, voiceprintAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_COUNT);
	}

	@Test
	void testDisabledNode() throws Exception {
		CortexNodeAdapter adapter = createAdapter(false);

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("voiceprint").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("voiceprint").isSkipped();
	}
}
