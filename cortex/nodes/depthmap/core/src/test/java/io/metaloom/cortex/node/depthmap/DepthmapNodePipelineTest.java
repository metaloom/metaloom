package io.metaloom.cortex.node.depthmap;

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
 * Pipeline integration test for {@link DepthmapNode}. The inference (an HTTP call to the FastAPI
 * sidecar) is stubbed, so this focuses on the pipeline adapter integration, event dispatch, and
 * output chaining - the last of which is what {@code scene-layout} depends on.
 */
class DepthmapNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_PATH = "/var/meta/depthmap_bin/abcd/hash.png";
	private static final String FAKE_META = "{\"model\":\"depth-anything/Depth-Anything-V2-Small-hf\","
		+ "\"convention\":\"NEARNESS\",\"width\":1024,\"height\":683,\"path\":\"" + FAKE_PATH + "\"}";

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
		DepthmapNodeOptions options = mock(DepthmapNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		DepthmapClient client = mock(DepthmapClient.class);

		DepthmapNode node = spy(new DepthmapNode(null, new CortexOptions(), options, client));

		if (enabled) {
			// Bypass the media-type gate and the real HTTP call.
			doReturn(true).when(node).isProcessable(any());
			doAnswer(invocation -> {
				NodeContext<LoomMedia> ctx = invocation.getArgument(0);
				ctx.output(DepthmapNode.OUT_FLAG, "DONE");
				ctx.output(DepthmapNode.OUT_MAP, FAKE_PATH);
				ctx.output(DepthmapNode.OUT_META, FAKE_META);
				return ctx.origin(ResultOrigin.COMPUTED).next();
			}).when(node).compute(any(), any());
		}

		return adapt(node);
	}

	@Test
	void testDepthmapOnImage() throws Exception {
		PipelineResult result = execute(media, createAdapter());

		assertThat(result)
			.isSuccess()
			.hasCompletedNode("depthmap")
			.hasNodeOutput("depthmap", DepthmapNode.OUT_MAP, FAKE_PATH)
			.hasNodeOutput("depthmap", DepthmapNode.OUT_META, FAKE_META);
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		execute(media, createAdapter());

		NodeCompletionEvent event = assertCompletionEvent("depthmap");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		execute(media, createAdapter());

		assertTrackingEvent("depthmap", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("depthmap", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testMetaReachesADownstreamConsumer() throws Exception {
		// This is the contract scene-layout is built on: the metadata travels as a plain string
		// output, which is what survives the pipeline's output-map serialization.
		CapturingNode consumer = new CapturingNode("consumer", DepthmapNode.OUT_META);

		PipelineResult result = execute(media, createAdapter(), consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_META);
	}

	@Test
	void testDisabledNode() throws Exception {
		PipelineResult result = execute(media, createAdapter(false));

		assertThat(result).isSuccess();
		assertThat(result).node("depthmap").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		PipelineResult result = executeDryRun(media, createAdapter());

		assertThat(result).isDryRun();
		assertThat(result).node("depthmap").isSkipped();
	}
}
