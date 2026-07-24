package io.metaloom.loom.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Spins up the full Loom stack (including MCP server on port 0) and interacts
 * with the MCP JSON-RPC API over HTTP. The real MCP tools are backed by an
 * actual PostgreSQL database with test fixtures.
 *
 * <p>The test discovers tools via {@code tools/list}, hands them to an LLM to
 * produce tool calls, and dispatches those tool calls back through the HTTP
 * MCP server via {@code tools/call}.</p>
 *
 * <p>Requires:
 * <ul>
 *   <li>A running Ollama instance at {@code http://127.0.0.1:11434}
 *       with the {@code gpt-oss:20b} model</li>
 *   <li>The test database pool provider (see {@code PoolSetupActionTest})</li>
 * </ul></p>
 */
public class MCPServerToolCallTest {

	private static final Logger log = LoggerFactory.getLogger(MCPServerToolCallTest.class);

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	/**
	 * Verify the MCP HTTP server responds to {@code initialize} and {@code tools/list},
	 * returning the real tools backed by the Loom database.
	 */
	@Test
	public void testServerInitializeAndListTools() throws Exception {
		int port = mcpPort();

		// Initialize
		JsonObject initResp = sendJsonRpc(port, "initialize", 1, new JsonObject()
			.put("protocolVersion", "2025-03-26")
			.put("clientInfo", new JsonObject().put("name", "test-client").put("version", "1.0")));

		log.info("Initialize response: {}", initResp.encodePrettily());
		assertEquals("2.0", initResp.getString("jsonrpc"));
		assertNotNull(initResp.getJsonObject("result"));
		assertEquals("2025-03-26", initResp.getJsonObject("result").getString("protocolVersion"));

		// List tools
		JsonObject toolsResp = sendJsonRpc(port, "tools/list", 2, new JsonObject());
		log.info("Tools list response: {}", toolsResp.encodePrettily());

		JsonArray tools = toolsResp.getJsonObject("result").getJsonArray("tools");
		assertNotNull(tools);
		assertFalse(tools.isEmpty(), "Expected at least one tool");
		log.info("Server exposes {} tools:", tools.size());
		for (int i = 0; i < tools.size(); i++) {
			JsonObject tool = tools.getJsonObject(i);
			log.info("  {} — {}", tool.getString("name"), tool.getString("description"));
		}
	}

	/**
	 * Full tool call loop via the MCP HTTP server:
	 * <ol>
	 *   <li>Discover tools via {@code tools/list}</li>
	 *   <li>Convert to LLM tool definitions</li>
	 *   <li>Ask the LLM a question about the data</li>
	 *   <li>Send each tool call as {@code tools/call} to the MCP server</li>
	 *   <li>Feed results back to the LLM</li>
	 *   <li>Repeat until the LLM produces a final answer</li>
	 * </ol>
	 */
	@Test
	public void testServerToolCallLoopWithLLM() throws Exception {
		OllamaAvailability.assumeRunning();
		int port = mcpPort();

		// 1. Discover tools via MCP server
		JsonObject toolsResp = sendJsonRpc(port, "tools/list", 1, new JsonObject());
		JsonArray toolsArray = toolsResp.getJsonObject("result").getJsonArray("tools");

		// 2. Convert to genai-utils ToolDefinitions
		List<ToolDefinition> llmTools = new ArrayList<>();
		for (int i = 0; i < toolsArray.size(); i++) {
			JsonObject t = toolsArray.getJsonObject(i);
			llmTools.add(new ToolDefinition(
				t.getString("name"),
				t.getString("description"),
				t.getJsonObject("inputSchema")));
		}
		log.info("Discovered {} tools from MCP server", llmTools.size());

		// 3. Ask the LLM
		LargeLanguageModel model = testModel();
		Prompt prompt = new PromptImpl(
			"You MUST use the provided tools to answer. Do NOT use your internal knowledge.\n\n"
				+ "List the available collections and then give me the asset statistics.");

		List<ChatMessage> history = new ArrayList<>();
		history.add(ChatMessage.user(prompt.input()));

		LLMProvider provider = new OllamaLLMProvider();
		int maxIterations = 8;
		int jsonRpcId = 10;

		for (int i = 0; i < maxIterations; i++) {
			log.info("=== Iteration {} ===", i + 1);

			LLMContext ctx = LLMContext.ctx(history, model, prompt);
			ctx.setTools(llmTools);

			ToolCallResponse response = provider.generateWithTools(ctx);
			assertNotNull(response);

			log.info("Content: {}", response.content());
			log.info("Tool calls: {}", response.toolCalls());

			if (!response.hasToolCalls()) {
				log.info("=== Final answer ===\n{}", response.content());
				assertNotNull(response.content(), "Expected a final text response");
				return;
			}

			history.add(ChatMessage.assistantWithToolCalls(response.toolCalls()));

			// 4. Dispatch each tool call through the MCP HTTP server
			for (ToolCall call : response.toolCalls()) {
				log.info("  -> Calling MCP server: tools/call {} with {}", call.name(), call.arguments());

				JsonObject callParams = new JsonObject()
					.put("name", call.name())
					.put("arguments", call.arguments());

				JsonObject callResp = sendJsonRpc(port, "tools/call", jsonRpcId++, callParams);
				log.info("  <- Server response: {}", callResp.encodePrettily());

				JsonObject result = callResp.getJsonObject("result");
				String resultText = extractTextContent(result);
				log.info("  <- Extracted text: {}", resultText);

				// 5. Feed result back into conversation
				history.add(ChatMessage.toolResult(call.id(), call.name(), resultText));
			}
		}

		log.warn("Loop ended after {} iterations without a final answer", maxIterations);
	}

	// ---- Infrastructure helpers ----

	/**
	 * Get the actual MCP server port (OS-assigned via port 0).
	 */
	private int mcpPort() {
		MCPService mcpService = loom.internal().boot().getMcpService();
		return mcpService.getServer().actualPort();
	}

	/**
	 * Send a JSON-RPC request to the MCP server over HTTP POST and return the response.
	 */
	private JsonObject sendJsonRpc(int port, String method, Object id, JsonObject params) throws Exception {
		JsonObject request = new JsonObject()
			.put("jsonrpc", "2.0")
			.put("method", method);
		if (id != null) {
			if (id instanceof Number n) {
				request.put("id", n.intValue());
			} else {
				request.put("id", id.toString());
			}
		}
		if (params != null) {
			request.put("params", params);
		}

		Vertx vertx = loom.internal().boot().getMcpService().vertx();
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		client.request(HttpMethod.POST, port, "127.0.0.1", "/mcp/message")
			.compose(req -> {
				req.putHeader("Content-Type", "application/json");
				return req.send(Buffer.buffer(request.encode()));
			})
			.compose(resp -> resp.body())
			.onSuccess(body -> future.complete(new JsonObject(body)))
			.onFailure(future::completeExceptionally);

		return future.get();
	}

	// ---- Shared helpers ----

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
