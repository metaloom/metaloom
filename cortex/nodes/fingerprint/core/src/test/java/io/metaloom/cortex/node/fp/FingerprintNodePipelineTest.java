package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractPipelineNodeTest;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.rest.model.asset.AssetResponse;

/**
 * Pipeline integration test for {@link FingerprintNode}.
 *
 * <p>The actual fingerprinting requires native Video4j libraries, so the
 * {@code compute()} method is stubbed to return a known fingerprint value.
 * This tests the pipeline adapter integration, event dispatch, output
 * chaining, and settings handling.</p>
 */
class FingerprintNodePipelineTest extends AbstractPipelineNodeTest {

	private static final String FAKE_FINGERPRINT = "aabbccddee001122";

	@TempDir
	File tempDir;

	private File testFile;
	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		testFile = new File(tempDir, "test-video.mp4");
		Files.write(testFile.toPath(), "fake-video-content".getBytes());
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

		CortexOptions cortexOptions = new CortexOptions();
		FingerprintNode node = spy(new FingerprintNode(null, cortexOptions, options, metaStorage));

		// Stub the compute method to avoid native Video4j calls
		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(FingerprintNode.OUTPUT_FINGERPRINT, FAKE_FINGERPRINT);
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
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

		List<String> receivedFp = new CopyOnWriteArrayList<>();
		AbstractPipelineNode downstream = new AbstractPipelineNode(
				"consumer", "Consumer", NodeMode.SEQUENTIAL, true, 1) {
			@Override
			public io.metaloom.cortex.pipeline.api.NodeResult process(LoomMedia media,
					Map<String, io.metaloom.cortex.pipeline.api.NodeResult> upstreamResults) {
				io.metaloom.cortex.pipeline.api.NodeResult fpResult = upstreamResults.get("fingerprint");
				String fp = fpResult != null ? fpResult.getOutput("fingerprint") : null;
				receivedFp.add(fp);
				return io.metaloom.cortex.pipeline.api.NodeResult.success(id(), 0,
						Map.of("received_fp", fp != null ? fp : ""));
			}
		};

		AssetSourceNode source = new AssetSourceNode(media);
		source.connectTo(fpAdapter);
		fpAdapter.connectTo(downstream);

		Pipeline pipeline = DefaultPipeline.builder("chaining-test")
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(receivedFp).containsExactly(FAKE_FINGERPRINT);
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

		AssetSourceNode source = new AssetSourceNode(media);
		source.connectTo(adapter);

		Pipeline pipeline = DefaultPipeline.builder("dryrun-test")
				.dryRun(true)
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, media);

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

		CortexOptions cortexOptions = new CortexOptions();
		FingerprintNode node = spy(new FingerprintNode(null, cortexOptions, options, metaStorage));

		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(FingerprintNode.OUTPUT_FINGERPRINT, FAKE_FINGERPRINT);
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		CortexNodeAdapter adapter = new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
		PipelineResult result = execute(media, adapter);

		// Node should skip because fingerprint was already computed
		assertThat(result).isSuccess();
		assertThat(result).node("fingerprint").isSkipped();
	}
}
