package io.metaloom.cortex.node.color;

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
 * Pipeline integration test for {@link DominantColorNode}. The colour computation is stubbed, so
 * this covers the adapter integration, event dispatch and output chaining only. The colour science
 * is covered by {@link ColorSpacesTest}, {@link ColorNamerTest} and {@link DominantColorNodeTest}.
 */
class DominantColorNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_RESULT = "{\"regions\":[{\"id\":\"whole\",\"dominant\":{\"hex\":\"#3B6EA5\"}}]}";

	private static final String FAKE_HEX = "#3B6EA5";

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
		DominantColorNodeOptions options = mock(DominantColorNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		DominantColorNode node = spy(new DominantColorNode(null, new CortexOptions(), options));

		if (enabled) {
			// Bypass the media-type gate and the decode + clustering.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(DominantColorNode.OUTPUT_DOMINANT_COLOR_RESULT, FAKE_RESULT);
				ctx.output(DominantColorNode.OUTPUT_DOMINANT_COLOR_HEX, FAKE_HEX);
				ctx.output(DominantColorNode.OUTPUT_DOMINANT_COLOR_TERM, "blue");
				ctx.output(DominantColorNode.OUTPUT_DOMINANT_COLOR_NAME_EN, "muted blue");
				ctx.output(DominantColorNode.OUTPUT_DOMINANT_COLOR_NAME_DE, "gedämpftes Blau");
				ctx.output(DominantColorNode.OUTPUT_DOMINANT_COLOR_REGIONS, 1);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testDominantColorOnImage() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("dominant-color")
			.hasNodeOutput("dominant-color", "dominant_color_result", FAKE_RESULT)
			.hasNodeOutput("dominant-color", "dominant_color_hex", FAKE_HEX)
			.hasNodeOutput("dominant-color", "dominant_color_term", "blue")
			.hasNodeOutput("dominant-color", "dominant_color_region_count", 1);
	}

	/**
	 * The German name has to survive the pipeline's output map as-is - it is the one output with
	 * non-ASCII characters in it.
	 */
	@Test
	void testTheGermanNameSurvivesThePipeline() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result).hasNodeOutput("dominant-color", "dominant_color_name_de", "gedämpftes Blau");
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("dominant-color");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("dominant-color", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("dominant-color", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testResultReachesADownstreamConsumer() throws Exception {
		// The hex is meant to feed a filter node or a search indexer, so it has to survive the
		// pipeline's output map as a plain string.
		CapturingNode consumer = new CapturingNode("consumer", "dominant-color", "dominant_color_hex");

		PipelineResult result = execute(media, createAdapter(), consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_HEX);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("dominant-color").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("dominant-color").isSkipped();
	}
}
