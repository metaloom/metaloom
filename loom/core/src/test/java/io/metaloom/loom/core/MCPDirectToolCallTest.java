package io.metaloom.loom.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.ai.genai.llm.ChatMessage;
import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.llm.ToolCall;
import io.metaloom.ai.genai.llm.ToolCallResponse;
import io.metaloom.ai.genai.llm.ToolDefinition;
import io.metaloom.ai.genai.llm.ollama.OllamaLLMProvider;
import io.metaloom.ai.genai.llm.prompt.Prompt;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.loom.mcp.MCPService;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.vertx.core.json.JsonObject;

/**
 * Test that directly interfaces with the MCP tool registry — no HTTP server
 * needed for dispatch. The real MCP tools (search_assets, list_collections,
 * asset_statistics, etc.) are backed by an actual PostgreSQL database with
 * test fixtures.
 *
 * <p>Uses {@link LoomCoreTestExtension} to spin up the full Dagger component
 * including Flyway migrations and test data, so tool calls hit real DAOs.</p>
 *
 * <p>Requires:
 * <ul>
 *   <li>A running Ollama instance at {@code http://127.0.0.1:11434}
 *       with the {@code gpt-oss:20b} model</li>
 *   <li>The test database pool provider (see {@code PoolSetupActionTest})</li>
 * </ul></p>
 */
public class MCPDirectToolCallTest {

	private static final Logger log = LoggerFactory.getLogger(MCPDirectToolCallTest.class);

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	/**
	 * Ask the LLM to use real MCP tools (backed by PostgreSQL) in a
	 * multi-turn tool call loop. The LLM should invoke tools like
	 * {@code search_assets} or {@code list_collections} and produce
	 * a final natural-language answer based on actual database content.
	 */
	@Test
	public void testDirectToolCallLoop() throws Exception {
		OllamaAvailability.assumeRunning();
		MCPService mcpService = loom.internal().boot().getMcpService();
		MCPToolRegistry toolRegistry = mcpService.getToolRegistry();

		// Convert registered MCP tool descriptors → genai-utils ToolDefinitions
		List<ToolDefinition> llmTools = toToolDefinitions(toolRegistry.listDescriptors());
		log.info("Registered {} tools for LLM: {}", llmTools.size(), toolRegistry.listToolNames());

		LargeLanguageModel model = testModel();
		Prompt prompt = new PromptImpl(
			"You MUST use the provided tools to answer. Do NOT use your internal knowledge.\n\n"
				+ "Questions:\n"
				+ "1. List the available collections.\n"
				+ "2. Search for assets and tell me how many you found.\n"
				+ "3. Give me the asset statistics.");

		// Mutable conversation history
		List<ChatMessage> history = new ArrayList<>();
		history.add(ChatMessage.user(prompt.input()));

		LLMProvider provider = new OllamaLLMProvider();
		int maxIterations = 8;

		for (int i = 0; i < maxIterations; i++) {
			log.info("=== Iteration {} ===", i + 1);

			LLMContext ctx = LLMContext.ctx(history, model, prompt);
			ctx.setTools(llmTools);

			ToolCallResponse response = provider.generateWithTools(ctx);
			assertNotNull(response);

			log.info("Content: {}", response.content());
			log.info("Tool calls: {}", response.toolCalls());

			if (!response.hasToolCalls()) {
				// Final answer produced
				log.info("=== Final answer ===\n{}", response.content());
				assertNotNull(response.content(), "Expected a final text response");
				return;
			}

			// Add the assistant message with its tool call requests
			history.add(ChatMessage.assistantWithToolCalls(response.toolCalls()));

			// Dispatch each tool call through the real MCP EventBus registry
			for (ToolCall call : response.toolCalls()) {
				log.info("  -> Dispatching tool: {} with args: {}", call.name(), call.arguments());

				JsonObject result = toolRegistry.dispatch(call.name(), call.arguments(), null)
					.toCompletionStage().toCompletableFuture().get();

				String resultText = extractTextContent(result);
				log.info("  <- Result: {}", resultText);

				history.add(ChatMessage.toolResult(call.id(), call.name(), resultText));
			}
		}

		log.warn("Loop ended after {} iterations without a final answer", maxIterations);
	}

	/**
	 * Single-turn test: verify the LLM produces at least one tool call
	 * when asked about assets.
	 */
	@Test
	public void testSingleTurnToolCall() throws Exception {
		OllamaAvailability.assumeRunning();
		MCPService mcpService = loom.internal().boot().getMcpService();
		MCPToolRegistry toolRegistry = mcpService.getToolRegistry();

		List<ToolDefinition> llmTools = toToolDefinitions(toolRegistry.listDescriptors());

		LargeLanguageModel model = testModel();
		Prompt prompt = new PromptImpl("List the available collections in the system.");
		LLMContext ctx = LLMContext.ctx(prompt, model);
		ctx.setTools(llmTools);

		LLMProvider provider = new OllamaLLMProvider();
		ToolCallResponse response = provider.generateWithTools(ctx);
		assertNotNull(response);

		log.info("Content: {}", response.content());
		log.info("Tool calls: {}", response.toolCalls());

		assertFalse(response.toolCalls().isEmpty(), "Expected at least one tool call for a collections query");
		for (ToolCall call : response.toolCalls()) {
			assertNotNull(call.name());
			log.info("Tool call: {} args={}", call.name(), call.arguments());
		}
	}

	// ---- Helpers ----

	private static LargeLanguageModel testModel() {
		return new LargeLanguageModel() {
			@Override
			public String id() {
				return "gpt-oss:20b";
			}

			@Override
			public String url() {
				return "http://127.0.0.1:11434";
			}

			@Override
			public long contextWindow() {
				return 4096;
			}

			@Override
			public io.metaloom.ai.genai.llm.LLMProviderType providerType() {
				return io.metaloom.ai.genai.llm.LLMProviderType.OLLAMA;
			}
		};
	}

	private static List<ToolDefinition> toToolDefinitions(Collection<MCPToolDescriptor> descriptors) {
		return descriptors.stream()
			.map(d -> new ToolDefinition(d.name(), d.description(), d.inputSchema()))
			.toList();
	}

	private static String extractTextContent(JsonObject result) {
		if (result == null) {
			return "";
		}
		var content = result.getJsonArray("content");
		if (content != null && !content.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < content.size(); i++) {
				JsonObject item = content.getJsonObject(i);
				if ("text".equals(item.getString("type"))) {
					if (!sb.isEmpty()) {
						sb.append("\n");
					}
					sb.append(item.getString("text", ""));
				}
			}
			return sb.toString();
		}
		return result.encode();
	}
}
