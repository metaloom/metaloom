package io.metaloom.cortex.node.sentiment;

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
 * Pipeline integration test for {@link SentimentNode}. The classification (an HTTP call to the FastAPI sidecar) and the upstream-text gate are
 * stubbed, so this focuses on the pipeline adapter integration, event dispatch, and output chaining. The text-in / label-out logic is covered by
 * {@link SentimentNodeTest}.
 */
class SentimentNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_LABEL = "NEGATIVE";

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "report.pdf", "fake-document");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		SentimentNodeOptions options = mock(SentimentNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		SentimentClient sentimentClient = mock(SentimentClient.class);

		SentimentNode node = spy(new SentimentNode(null, new CortexOptions(), options, sentimentClient));

		if (enabled) {
			// Bypass the upstream-text gate and the real HTTP call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(SentimentNode.OUT_LABEL, FAKE_LABEL);
				ctx.output(SentimentNode.OUT_SCORE, 0.87d);
				ctx.output(SentimentNode.OUT_RESULT, "{\"label\":\"" + FAKE_LABEL + "\"}");
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testSentimentOnDocument() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("sentiment")
			.hasNodeOutput("sentiment", SentimentNode.OUT_LABEL, FAKE_LABEL);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("sentiment");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		assertTrackingEvent("sentiment", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("sentiment", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter sentimentAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", SentimentNode.OUT_LABEL);

		PipelineResult result = execute(media, sentimentAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_LABEL);
	}

	@Test
	void testDisabledNode() throws Exception {
		CortexNodeAdapter adapter = createAdapter(false);

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("sentiment").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("sentiment").isSkipped();
	}
}
