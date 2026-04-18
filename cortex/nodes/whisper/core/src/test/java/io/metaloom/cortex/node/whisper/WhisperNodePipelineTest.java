package io.metaloom.cortex.node.whisper;

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
 * Pipeline integration test for {@link WhisperNode}.
 *
 * <p>The actual speech-to-text processing requires native Whisper.cpp
 * libraries, so the {@code compute()} method is stubbed. This tests the
 * pipeline adapter integration, event dispatch, output chaining, and
 * media type filtering (audio/video only).</p>
 */
class WhisperNodePipelineTest extends AbstractPipelineNodeTest {

	private static final String FAKE_WHISPER_JSON = "{\"segments\":[{\"text\":\"Hello world\",\"from\":0,\"to\":5000}]}";

	@TempDir
	File tempDir;

	private File testFile;
	private StubLoomMedia videoMedia;
	private StubLoomMedia audioMedia;

	@BeforeEach
	void setUpTestData() throws IOException {
		testFile = new File(tempDir, "test-audio.wav");
		Files.write(testFile.toPath(), "fake-audio-content".getBytes());
		videoMedia = new StubLoomMedia(testFile.getAbsolutePath(), true, false, false, false);
		audioMedia = new StubLoomMedia(testFile.getAbsolutePath(), false, false, true, false);
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter(true);
	}

	private CortexNodeAdapter createAdapter(boolean enabled) throws Exception {
		WhisperOptions options = mock(WhisperOptions.class);
		when(options.isEnabled()).thenReturn(enabled);

		WhisperMediaProcessor processor = mock(WhisperMediaProcessor.class);

		CortexOptions cortexOptions = new CortexOptions();
		WhisperNode node = spy(new WhisperNode(null, cortexOptions, options, processor));

		// Stub the compute method to avoid native WhisperCpp calls
		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(WhisperNode.OUTPUT_WHISPER_RESULT, FAKE_WHISPER_JSON);
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}).when(node).compute(any(), any());

		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testWhisperOnVideo() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("whisper")
				.hasNodeOutput("whisper", "whisper_result", FAKE_WHISPER_JSON);
	}

	@Test
	void testWhisperOnAudio() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(audioMedia, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("whisper");

		assertThat(result).node("whisper")
				.isCompleted()
				.hasOutput("whisper_result", FAKE_WHISPER_JSON)
				.hasOutputCount(1);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(videoMedia, adapter);

		NodeCompletionEvent event = assertCompletionEvent("whisper");
		assertThat(event.getResult().getState()).isEqualTo(NodeState.COMPLETED);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(videoMedia, adapter);

		assertTrackingEvent("whisper", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("whisper", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter whisperAdapter = createAdapter();

		List<String> receivedTranscripts = new CopyOnWriteArrayList<>();
		AbstractPipelineNode downstream = new AbstractPipelineNode(
				"consumer", "Consumer", NodeMode.SEQUENTIAL, true, 1) {
			@Override
			public io.metaloom.cortex.pipeline.api.NodeResult process(LoomMedia media,
					Map<String, io.metaloom.cortex.pipeline.api.NodeResult> upstreamResults) {
				io.metaloom.cortex.pipeline.api.NodeResult whisperResult = upstreamResults.get("whisper");
				String json = whisperResult != null ? whisperResult.getOutput("whisper_result") : null;
				receivedTranscripts.add(json);
				return io.metaloom.cortex.pipeline.api.NodeResult.success(id(), 0,
						Map.of("received_transcript", json != null ? json : ""));
			}
		};

		AssetSourceNode source = new AssetSourceNode(videoMedia);
		source.connectTo(whisperAdapter);
		whisperAdapter.connectTo(downstream);

		Pipeline pipeline = DefaultPipeline.builder("chaining-test")
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, videoMedia);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(receivedTranscripts).containsExactly(FAKE_WHISPER_JSON);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testDocumentMediaSkipped() throws Exception {
		StubLoomMedia docMedia = new StubLoomMedia(testFile.getAbsolutePath(), false, false, false, true);
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(docMedia, adapter);

		// Whisper only processes audio and video
		assertThat(result).isSuccess();
		assertThat(result).node("whisper").isSkipped();
	}

	@Test
	void testDisabledNode() throws Exception {
		CortexNodeAdapter adapter = createAdapter(false);

		PipelineResult result = execute(videoMedia, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("whisper").isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		AssetSourceNode source = new AssetSourceNode(videoMedia);
		source.connectTo(adapter);

		Pipeline pipeline = DefaultPipeline.builder("dryrun-test")
				.dryRun(true)
				.source(source)
				.build();

		PipelineResult result = executor.execute(pipeline, videoMedia);

		assertThat(result).isDryRun();
		assertThat(result).node("whisper").isSkipped();
	}
}
