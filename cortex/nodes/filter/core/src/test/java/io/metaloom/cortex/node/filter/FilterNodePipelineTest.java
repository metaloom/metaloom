package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.Map;

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
 * Adapter integration for {@link FilterNode}: events, chaining off a bucket port, and the two skip
 * paths. The classification itself is stubbed — {@link FilterNodeTest} covers what the model's answer
 * turns into.
 */
class FilterNodePipelineTest extends AbstractNodeChainTest {

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "asset.txt", "Guten Tag");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		FilterNodeOptions options = mock(FilterNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		FilterNode node = spy(new FilterNode(null, new CortexOptions(), options,
			Map.of(FilterBy.LANGUAGE, () -> new LanguageFilterStrategy(StubLLMProvider.answering("de")))));

		if (enabled) {
			// Bypass the "configured?" gate and the model round trip.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(FilterNode.bucketPort("de"), media.absolutePath());
				ctx.output(FilterNode.OUT_PASSED, true);
				ctx.output(FilterNode.OUT_BUCKET, "de");
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testRoutesTheItemOntoItsBucketPort() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("filter")
			.hasNodeOutput("filter", FilterNode.bucketPort("de"), media.absolutePath());
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("filter");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("filter", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("filter", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	/**
	 * The shape that matters in production: downstream work hangs off the bucket port, and only the
	 * branch that was written delivers anything.
	 */
	@Test
	void testABucketPortChainsToADownstreamConsumer() throws Exception {
		CortexNodeAdapter filter = createAdapter();
		CapturingNode german = new CapturingNode("german", FilterNode.bucketPort("de"));

		PipelineResult result = execute(media, filter, german);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(german.capturedValues()).containsExactly(media.absolutePath());
	}

	/**
	 * The node writes exactly one bucket port and stays silent on the rest. That silence is what the
	 * Loom engine turns into a skip.
	 *
	 * <p>
	 * The skip itself is <strong>not</strong> observable here: this in-process chain has no routing —
	 * it runs every node and hands a downstream consumer a null for a port nobody wrote.
	 * {@code PipelineRunEnginePortRoutingTest} is where the branch is actually closed. What this
	 * asserts is the node's half of that contract: the unwritten port really is unwritten, with
	 * exactly three outputs rather than an empty-valued fourth.
	 * </p>
	 */
	@Test
	void testOnlyOneBucketPortIsWritten() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result).isSuccess();
		assertThat(result).node("filter")
			.hasOutput(FilterNode.bucketPort("de"))
			.hasOutputCount(3);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("filter").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("filter").isSkipped();
	}
}
