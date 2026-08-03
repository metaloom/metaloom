package io.metaloom.cortex.node.metadata;

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
 * Adapter integration for {@link MetadataNode}: event dispatch, output chaining and the skip paths.
 * The extraction itself is stubbed - that is {@link MetadataNodeTest}'s job - so what is under test
 * here is that the node behaves like a node.
 */
class MetadataNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_TEXT = "Sunrise over Fuji";

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "fuji.jpg", "fake-image");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), false, true, false, false);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		MetadataNodeOptions options = mock(MetadataNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		MetadataNode node = spy(new MetadataNode(null, new CortexOptions(), options));

		if (enabled) {
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(MetadataNode.OUT_METADATA, "{\"v\":1}");
				ctx.output(MetadataNode.OUT_TEXT, FAKE_TEXT);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testMetadataOnImage() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("metadata")
			.hasNodeOutput("metadata", MetadataNode.OUT_TEXT, FAKE_TEXT);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("metadata");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("metadata", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("metadata", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testTextChainsIntoADownstreamConsumer() throws Exception {
		// The whole point of the text port: a downstream translate/sentiment node consumes an
		// ingested caption without knowing anything about EXIF.
		CapturingNode consumer = new CapturingNode("consumer", MetadataNode.OUT_TEXT);

		PipelineResult result = execute(media, createAdapter(), consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_TEXT);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("metadata").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("metadata").isSkipped();
	}
}
