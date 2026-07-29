package io.metaloom.cortex.node.scenelayout;

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
 * Pipeline integration test for {@link SceneLayoutNode}. The layout computation is stubbed, so this
 * focuses on the pipeline adapter integration, event dispatch, and output chaining. The geometry is
 * covered by {@link RelationSolverTest} and {@link SceneLayoutNodeTest}.
 */
class SceneLayoutNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_RESULT = "{\"objects\":[],\"relations\":[],\"phrases\":[\"face-0 is in front of face-1\"]}";

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
		SceneLayoutNodeOptions options = mock(SceneLayoutNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		SceneLayoutNode node = spy(new SceneLayoutNode(null, new CortexOptions(), options));

		if (enabled) {
			// Bypass the media-type gate and the depth/detection gathering.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(SceneLayoutNode.OUT_RESULT, FAKE_RESULT);
				ctx.output(SceneLayoutNode.OUT_OBJECT_COUNT, 2L);
				ctx.output(SceneLayoutNode.OUT_RELATION_COUNT, 3L);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testSceneLayoutOnImage() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("scene-layout")
			.hasNodeOutput("scene-layout", SceneLayoutNode.OUT_RESULT, FAKE_RESULT)
			.hasNodeOutput("scene-layout", SceneLayoutNode.OUT_OBJECT_COUNT, 2L);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("scene-layout");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("scene-layout", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("scene-layout", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testResultReachesADownstreamConsumer() throws Exception {
		// The layout is meant to feed captioning / LLM prompts, so it has to survive the pipeline's
		// output-map serialization as a plain string.
		CapturingNode consumer = new CapturingNode("consumer", SceneLayoutNode.OUT_RESULT);

		PipelineResult result = execute(media, createAdapter(), consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_RESULT);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("scene-layout").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("scene-layout").isSkipped();
	}
}
