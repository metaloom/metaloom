package io.metaloom.cortex.node.llm;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * Pipeline integration test for {@link LLMNode}.
 *
 * <p>The actual LLM inference requires an Ollama server, so the
 * {@code compute()} method is stubbed to return known JSON output.
 * This tests the pipeline adapter integration, event dispatch, output
 * chaining, and prompt configuration.</p>
 */
class LLMNodePipelineTest extends AbstractNodeChainTest {

	private static final String FAKE_LLM_RESULT = "{\"title\":\"Test Video\",\"genre\":\"documentary\",\"year\":\"2024\"}";

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		media = StubLoomMedia.ofBytes(tempDir, "My_Documentary_2024_1080p.mp4", "fake-video-content");
	}

	private CortexNodeAdapter createAdapter() throws Exception {
		return createAdapter("default");
	}

	private CortexNodeAdapter createAdapter(String promptId) throws Exception {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setEnabled(true);

		LLMNodePrompt prompt = new LLMNodePrompt();
		prompt.setModel("gemma2:27b");
		prompt.setPrompt("Extract metadata from ${name}");
		options.setPrompts(Map.of(promptId, prompt));

		LLMNode node = spy(new LLMNode(null, new CortexOptions(), options, new io.metaloom.ai.genai.llm.ollama.OllamaLLMProvider()));

		// Stub the compute method to avoid Ollama HTTP calls
		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			for (String id : options.getPrompts().keySet()) {
				ctx.output(LLMNode.resultKey(id), FAKE_LLM_RESULT);
			}
			return NodeResult.success(ctx.outputs());
		}).when(node).compute(any(), any());

		return adapt(node);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testLLMExecution() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("llm")
				.hasNodeOutputKey("llm", "llm_result_default");
	}

	@Test
	void testNodeOutput() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = execute(media, adapter);

		assertThat(result).node("llm")
				.isCompleted()
				.hasOutput("llm_result_default", FAKE_LLM_RESULT)
				.hasOutputCount(1);
	}

	@Test
	void testMultiplePrompts() throws Exception {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setEnabled(true);

		LLMNodePrompt prompt1 = new LLMNodePrompt();
		prompt1.setModel("gemma2:27b");
		prompt1.setPrompt("Describe ${name}");

		LLMNodePrompt prompt2 = new LLMNodePrompt();
		prompt2.setModel("gemma2:27b");
		prompt2.setPrompt("Categorize ${name}");

		options.setPrompts(Map.of("describe", prompt1, "categorize", prompt2));

		CortexOptions cortexOptions = new CortexOptions();
		LLMNode node = spy(new LLMNode(null, cortexOptions, options, new io.metaloom.ai.genai.llm.ollama.OllamaLLMProvider()));

		doAnswer(invocation -> {
			NodeContext<LoomMedia> ctx = invocation.getArgument(0);
			ctx.output(LLMNode.resultKey("describe"), "{\"description\":\"A documentary\"}");
			ctx.output(LLMNode.resultKey("categorize"), "{\"category\":\"documentary\"}");
			return NodeResult.success(ctx.outputs());
		}).when(node).compute(any(), any());

		CortexNodeAdapter adapter = adapt(node);
		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("llm")
				.isCompleted()
				.hasOutput("llm_result_describe")
				.hasOutput("llm_result_categorize")
				.hasOutputCount(2);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("llm");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		execute(media, adapter);

		assertTrackingEvent("llm", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("llm", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter llmAdapter = createAdapter();
		CapturingNode consumer = new CapturingNode("consumer", "llm", "llm_result_default");

		PipelineResult result = execute(media, llmAdapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(FAKE_LLM_RESULT);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter();

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("llm").isSkipped();
	}
}
