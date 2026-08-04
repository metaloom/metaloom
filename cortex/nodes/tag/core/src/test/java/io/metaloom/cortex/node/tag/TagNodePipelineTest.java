package io.metaloom.cortex.node.tag;

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

import javax.inject.Provider;

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
import io.vertx.core.json.JsonObject;

/**
 * Adapter integration for {@link TagNode}: events, chaining off the {@code applied} port, and the two
 * skip paths. What the rules decide is stubbed here — {@link TagNodeTest} covers that.
 */
class TagNodePipelineTest extends AbstractNodeChainTest {

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	private static final String RECORD = new JsonObject()
		.put("tagBy", "RULES")
		.put("collection", "quality")
		.put("applied", new io.vertx.core.json.JsonArray().add(new JsonObject().put("tag", "blurry")))
		.encode();

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "asset.jpg", "some bytes");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, false, false, true);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		TagNodeOptions options = mock(TagNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		Provider<TagStrategy> rules = RulesTagStrategy::new;
		TagNode node = spy(new TagNode(null, new CortexOptions(), options, Map.of(TagBy.RULES, rules)));

		if (enabled) {
			// Bypass the "configured?" gate and the evaluation.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(TagNode.OUT_APPLIED, RECORD);
				ctx.output(TagNode.OUT_COUNT, 1L);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testEmitsTheVerdictAndTheCount() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("tag")
			.hasNodeOutput("tag", TagNode.OUT_APPLIED, RECORD)
			.hasNodeOutput("tag", TagNode.OUT_COUNT, 1L);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("tag");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("tag", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("tag", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	/** The count is what a downstream filter would branch on: "did this item get tagged at all?". */
	@Test
	void testTheCountChainsToADownstreamConsumer() throws Exception {
		CortexNodeAdapter tag = createAdapter();
		CapturingNode counter = new CapturingNode("counter", TagNode.OUT_COUNT);

		PipelineResult result = execute(media, tag, counter);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(counter.capturedValues()).containsExactly(1L);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("tag").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("tag").isSkipped();
	}
}
