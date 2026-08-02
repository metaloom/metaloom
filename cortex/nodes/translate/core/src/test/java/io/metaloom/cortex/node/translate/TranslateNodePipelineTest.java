package io.metaloom.cortex.node.translate;

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

import io.metaloom.ai.genai.llm.LLMProvider;
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
 * Pipeline integration test for {@link TranslateNode}. The model call and the upstream-text gate are
 * stubbed, so this focuses on adapter integration, event dispatch and output chaining — the part
 * that matters downstream is that {@code translation} can be wired into another node's text input,
 * which is exactly how a dubbing graph reaches {@code tts}. The translation logic itself is covered
 * by {@link TranslateNodeTest}.
 */
class TranslateNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_TRANSLATION = "The customer service was a disaster.";

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "interview.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		TranslateNodeOptions options = mock(TranslateNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		LLMProvider provider = mock(LLMProvider.class);

		TranslateNode node = spy(new TranslateNode(null, new CortexOptions(), options, provider));

		if (enabled) {
			// Bypass the upstream-text gate and the real model call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(TranslateNode.OUT_TRANSLATION, FAKE_TRANSLATION);
				ctx.output(TranslateNode.OUT_LANGUAGE, "en");
				ctx.output(TranslateNode.OUT_RESULT, "{\"text\":\"" + FAKE_TRANSLATION + "\"}");
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testTranslateOnVideoTranscript() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("translate")
			.hasNodeOutput("translate", TranslateNode.OUT_TRANSLATION, FAKE_TRANSLATION);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("translate");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		assertTrackingEvent("translate", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("translate", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testTranslationChainsIntoADownstreamTextConsumer() throws Exception {
		CortexNodeAdapter translateAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", TranslateNode.OUT_TRANSLATION);

		PipelineResult result = execute(media, translateAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_TRANSLATION);
	}

	@Test
	void testDisabledNode() throws Exception {
		CortexNodeAdapter adapter = createAdapter(false);

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("translate").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("translate").isSkipped();
	}
}
