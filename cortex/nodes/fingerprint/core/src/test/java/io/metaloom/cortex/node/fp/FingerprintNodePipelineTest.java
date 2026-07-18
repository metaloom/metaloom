package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * Pipeline integration test for {@link FingerprintNode}.
 *
 * <p>The actual fingerprinting requires native Video4j libraries, so the
 * {@code compute()} method is stubbed to return a known fingerprint value.
 * This tests the pipeline adapter integration, event dispatch, output
 * chaining, and settings handling.</p>
 */
class FingerprintNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_FINGERPRINT = "aabbccddee001122";

	@TempDir
	File tempDir;

	private File testFile;
	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "test-video.mp4", "fake-video-content");
		testFile = backing.file();
		media = new StubLoomMedia(testFile.getAbsolutePath(), true, false, false, false);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		FingerprintNodeOptions options = mock(FingerprintNodeOptions.class);
		when(options.isEnabled()).thenReturn(enabled);
		when(options.isProcessIncomplete()).thenReturn(true);

		FingerprintMetaStorage metaStorage = mock(FingerprintMetaStorage.class);
		when(metaStorage.hasFingerprint(any())).thenReturn(false);

		FingerprintNode node = spy(new FingerprintNode(null, new CortexOptions(), options, metaStorage));

		// Stub the compute method to avoid native Video4j calls
		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(FingerprintNode.OUTPUT_FINGERPRINT, FAKE_FINGERPRINT);
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		return adapt(node);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testFingerprintComputation() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("fingerprint")
				.hasNodeOutput("fingerprint", "fingerprint", FAKE_FINGERPRINT);
	}

	@Test
	void testNodeOutput() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result).node("fingerprint")
				.isCompleted()
				.hasOutput("fingerprint", FAKE_FINGERPRINT)
				.hasOutputCount(1);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("fingerprint");
		assertThat(event.getResult().getState()).isEqualTo(NodeState.COMPLETED);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		assertTrackingEvent("fingerprint", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("fingerprint", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter fpAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", "fingerprint", "fingerprint");

		PipelineResult result = execute(media, fpAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_FINGERPRINT);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testNonVideoMediaSkipped() throws Exception {
		// Fingerprint only works on video
		StubLoomMedia imageMedia = new StubLoomMedia(testFile.getAbsolutePath(), false, true, false, false);
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(imageMedia, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("fingerprint").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("fingerprint").isSkipped();
	}

	@Test
	void testAlreadyProcessedSkipped() throws Exception {
		FingerprintNodeOptions options = mock(FingerprintNodeOptions.class);
		when(options.isEnabled()).thenReturn(true);
		when(options.isProcessIncomplete()).thenReturn(true);

		FingerprintMetaStorage metaStorage = mock(FingerprintMetaStorage.class);
		when(metaStorage.hasFingerprint(any())).thenReturn(true);

		FingerprintNode node = spy(new FingerprintNode(null, new CortexOptions(), options, metaStorage));

		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(FingerprintNode.OUTPUT_FINGERPRINT, FAKE_FINGERPRINT);
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		CortexNodeAdapter adapter = adapt(node);
		PipelineResult result = execute(media, adapter);

		// Node should skip because fingerprint was already computed
		assertThat(result).isSuccess();
		assertThat(result).node("fingerprint").isSkipped();
	}
}
