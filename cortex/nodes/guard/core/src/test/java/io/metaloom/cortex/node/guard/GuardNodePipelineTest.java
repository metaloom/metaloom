package io.metaloom.cortex.node.guard;

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
 * Pipeline integration test for {@link GuardNode}. The backend call and the input gate are stubbed,
 * so this focuses on adapter integration, event dispatch and output chaining — the part that matters
 * downstream is that the verdict reaches a consumer, which is how a guard gates a publish branch.
 * The classification itself is covered by {@link GuardNodeTest} and {@link GuardDialectTest}.
 */
class GuardNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_RESULT = "{\"safe\":false,\"score\":0.93}";

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "document.pdf", "fake-doc");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		GuardNodeOptions options = mock(GuardNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		GuardNode node = spy(new GuardNode(null, new CortexOptions(), options, mock(GuardClient.class)));

		if (enabled) {
			// Bypass the input gate and the real backend call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(GuardNode.OUT_SAFE, Boolean.FALSE);
				ctx.output(GuardNode.OUT_LABEL, "unsafe");
				ctx.output(GuardNode.OUT_SCORE, 0.93d);
				ctx.outputElement(GuardNode.OUT_CATEGORIES, GuardCategory.INDISCRIMINATE_WEAPONS.name());
				ctx.output(GuardNode.OUT_RESULT, FAKE_RESULT);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testGuardOnADocument() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("guard")
			.hasNodeOutput("guard", GuardNode.OUT_LABEL, "unsafe");
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("guard");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("guard", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("guard", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testVerdictChainsIntoADownstreamConsumer() throws Exception {
		CortexNodeAdapter guardAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", GuardNode.OUT_RESULT);

		PipelineResult result = execute(media, guardAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_RESULT);
	}

	@Test
	void testFlaggedCategoriesFanOutOneElementEach() throws Exception {
		// MANY, so the categories feed the tag node's labels input without a shim in between.
		CortexNodeAdapter guardAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", GuardNode.OUT_CATEGORIES);

		execute(media, guardAdapter, consumer);

		assertThat(consumer.capturedValues()).containsExactly(GuardCategory.INDISCRIMINATE_WEAPONS.name());
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("guard").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("guard").isSkipped();
	}
}
