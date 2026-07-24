package io.metaloom.loom.test.integration.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.Chunk;
import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.llm.impl.LargeLanguageModelImpl;
import io.metaloom.ai.genai.llm.prompt.Prompt;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.ai.genai.llm.vllm.VLLMLLMProvider;
import io.metaloom.ai.genai.mockllm.MockLLMServer;
import io.vertx.core.json.JsonObject;

/**
 * Integration test that drives the real OpenAI-compatible LLM path used by the metaloom chat
 * agent ({@code loom/agent/chat} → {@link VLLMLLMProvider}) against the {@link MockLLMServer},
 * proving that path works end-to-end without a live model backend.
 *
 * <p>This test only exercises the LLM client, so it needs neither the pooled test database nor a
 * running Loom server.
 */
class MockLLMServerIntegrationTest {

	private final LLMProvider provider = new VLLMLLMProvider();

	private static LargeLanguageModel modelFor(MockLLMServer server) {
		return new LargeLanguageModelImpl("mock-model", server.baseUrl(), 2048, LLMProviderType.VLLM);
	}

	private static LLMContext chatCtx(MockLLMServer server, String userMessage) {
		Prompt prompt = new PromptImpl(userMessage);
		return LLMContext.ctx(prompt, modelFor(server));
	}

	@Test
	void testBlockingGeneration() {
		try (MockLLMServer server = MockLLMServer.create(0)
				.addResponse("A caption describing the image.")
				.start()) {

			String text = provider.generate(chatCtx(server, "Describe this image"));

			assertEquals("A caption describing the image.", text);
			assertEquals(0, server.remainingResponses());
		}
	}

	@Test
	void testStructuredGeneration() {
		JsonObject expected = new JsonObject()
				.put("label", "cat")
				.put("confidence", 0.97);
		try (MockLLMServer server = MockLLMServer.create(0)
				.addStructuredResponse(expected)
				.start()) {

			JsonObject result = provider.generateJson(chatCtx(server, "Classify this image as JSON"));

			assertEquals("cat", result.getString("label"));
			assertEquals(0.97, result.getDouble("confidence"));
		}
	}

	@Test
	void testStreamingGeneration() {
		String expected = "Streaming caption produced token by token.";
		try (MockLLMServer server = MockLLMServer.create(0)
				.addResponse(expected)
				.start()) {

			List<Chunk> chunks = provider.generateStream(chatCtx(server, "Stream a caption"))
					.toList()
					.blockingGet();

			String collected = chunks.stream()
					.filter(c -> !c.isThinking())
					.map(Object::toString)
					.reduce("", String::concat);

			assertEquals(expected, collected);
			assertTrue(chunks.size() > 1, "Expected the response to arrive as multiple streamed chunks");
		}
	}
}
