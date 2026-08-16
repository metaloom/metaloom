package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.ToolCall;
import io.metaloom.loom.agent.chat.loop.TurnListener;
import io.metaloom.loom.agent.chat.loop.TurnResult;
import io.metaloom.loom.agent.chat.loop.TurnStreamer;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.chat.ChatCreateRequest;
import io.metaloom.loom.rest.model.chat.ChatResponse;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests the SSE streaming route of the chat agent ({@code POST /api/v1/chats/:uuid/stream}) against the full stack with a scripted {@link TurnStreamer} —
 * tool dispatch runs through the real MCP tool registry and database, only the LLM is faked.
 */
public class ChatStreamEndpointTest extends AbstractEndpointTest {

	private record SseEvent(String type, JsonObject data) {
	}

	private record HttpResult(int statusCode, String contentType, String body) {
	}

	private String loginAdminToken(LoomHttpClient client) throws Exception {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
		return loginResponse.getToken();
	}

	private UUID createChat(LoomHttpClient client) throws Exception {
		ChatCreateRequest request = new ChatCreateRequest();
		request.setTitle("Stream test chat");
		ChatResponse chat = client.createChat(request).sync().body();
		return chat.getUuid();
	}

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	private Vertx vertx() {
		return loom.internal().boot().getMcpService().vertx();
	}

	private CompletableFuture<HttpResult> request(HttpMethod method, String path, String token, JsonObject body) {
		HttpClient client = vertx().createHttpClient();
		CompletableFuture<HttpResult> future = new CompletableFuture<>();
		client.request(method, restPort(), "localhost", path)
			.compose(req -> {
				req.putHeader("Authorization", "Bearer " + token);
				if (body != null) {
					req.putHeader("Content-Type", "application/json");
					return req.send(Buffer.buffer(body.encode()));
				}
				return req.send();
			})
			.compose(resp -> resp.body().map(buffer -> new HttpResult(resp.statusCode(), resp.getHeader("Content-Type"), buffer.toString())))
			.onSuccess(future::complete)
			.onFailure(future::completeExceptionally);
		return future;
	}

	private CompletableFuture<HttpResult> stream(String token, UUID chatUuid, String message) {
		return request(HttpMethod.POST, "/api/v1/chats/" + chatUuid + "/stream", token,
			new JsonObject().put("message", message).put("skillUuids", new JsonArray()));
	}

	private static List<SseEvent> parseSse(String body) {
		List<SseEvent> events = new ArrayList<>();
		for (String frame : body.split("\n\n")) {
			String type = null;
			String data = null;
			for (String line : frame.split("\n")) {
				if (line.startsWith("event: ")) {
					type = line.substring("event: ".length());
				} else if (line.startsWith("data: ")) {
					data = line.substring("data: ".length());
				}
			}
			if (type != null && data != null) {
				events.add(new SseEvent(type, new JsonObject(data)));
			}
		}
		return events;
	}

	private static TurnStreamer scripted(List<TurnResult> results) {
		Deque<TurnResult> queue = new ArrayDeque<>(results);
		return (ctx, listener) -> {
			TurnResult result = queue.isEmpty() ? new TurnResult("done", null, List.of()) : queue.pop();
			if (result.reasoning() != null) {
				listener.onReasoningDelta(result.reasoning());
			}
			if (result.text() != null && !result.text().isBlank()) {
				listener.onTextDelta(result.text());
			}
			return result;
		};
	}

	@Test
	public void testStreamEventSequenceAndPersistence() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			String token = loginAdminToken(client);
			UUID chatUuid = createChat(client);

			loom.internal().agentService().setTurnStreamerFactory(() -> scripted(List.of(
				new TurnResult(null, "Looking for assets.",
					List.of(new ToolCall("c1", "search_assets", new JsonObject().put("query", "bigbuckbunny").put("limit", 3)))),
				new TurnResult("Here is what I **found**.", null, List.of()))));

			HttpResult result = stream(token, chatUuid, "What assets do we have?").get(30, TimeUnit.SECONDS);
			assertEquals(200, result.statusCode());
			assertTrue(result.contentType().startsWith("text/event-stream"), "The response must be an SSE stream");

			List<SseEvent> events = parseSse(result.body());
			List<String> types = events.stream().map(SseEvent::type).toList();
			assertEquals(List.of(
				"agent_start",
				"turn_start",
				// Context accounting of the prompt about to go out — estimated, because the eviction
				// decision it explains is made before the server can report anything (CHAT.md §4.4).
				"context",
				"reasoning_delta",
				"tool_start",
				"tool_end",
				"turn_end",
				"turn_start",
				"context",
				"text_delta",
				"turn_end",
				"message_end",
				"agent_end"), types);

			// The real search_assets tool ran against the fixture DB and attached references
			SseEvent toolEnd = events.stream().filter(e -> e.type().equals("tool_end")).findFirst().orElseThrow();
			assertFalse(toolEnd.data().getBoolean("isError"));
			assertFalse(toolEnd.data().getJsonArray("references").isEmpty(), "The tool references must be streamed live");

			SseEvent agentEnd = events.get(events.size() - 1);
			assertEquals("completed", agentEnd.data().getString("status"));

			// The exchange is persisted server-side
			ChatResponse chat = client.loadChat(chatUuid).sync().body();
			JsonArray messages = chat.getMessages();
			assertEquals(2, messages.size());
			assertEquals("What assets do we have?", messages.getJsonObject(0).getString("content"));
			JsonObject assistantMsg = messages.getJsonObject(1);
			assertEquals("Here is what I **found**.", assistantMsg.getString("content"));
			assertEquals("Looking for assets.", assistantMsg.getString("reasoning"));
			assertNotNull(assistantMsg.getJsonArray("toolCalls"));
			assertNotNull(assistantMsg.getJsonArray("references"));
		}
	}

	@Test
	public void testUnknownChat() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			String token = loginAdminToken(client);
			HttpResult result = stream(token, UUID.randomUUID(), "Hello").get(30, TimeUnit.SECONDS);
			assertEquals(404, result.statusCode());
		}
	}

	@Test
	public void testEmptyMessage() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			String token = loginAdminToken(client);
			UUID chatUuid = createChat(client);
			HttpResult result = request(HttpMethod.POST, "/api/v1/chats/" + chatUuid + "/stream", token,
				new JsonObject().put("message", "")).get(30, TimeUnit.SECONDS);
			assertEquals(400, result.statusCode());
		}
	}

	@Test
	public void testBusyGuardAndCancel() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			String token = loginAdminToken(client);
			UUID chatUuid = createChat(client);

			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			// Stands in for a provider stream that only ends when the subscription is disposed: the turn blocks until cancel() releases it, so the run can
			// only reach "aborted" if the DELETE actually interrupted the turn in flight.
			loom.internal().agentService().setTurnStreamerFactory(() -> new TurnStreamer() {
				@Override
				public TurnResult streamTurn(LLMContext ctx, TurnListener listener) {
					started.countDown();
					try {
						release.await(30, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return new TurnResult("late answer", null, List.of(new ToolCall("c1", "search_assets", new JsonObject())));
				}

				@Override
				public void cancel() {
					release.countDown();
				}
			});

			// First run blocks inside the turn
			CompletableFuture<HttpResult> first = stream(token, chatUuid, "First message");
			assertTrue(started.await(30, TimeUnit.SECONDS), "The first run should have started");

			// A concurrent run on the same chat is rejected
			HttpResult second = stream(token, chatUuid, "Second message").get(30, TimeUnit.SECONDS);
			assertEquals(409, second.statusCode(), "A concurrent run on the same chat must be rejected");

			// Explicit cancel aborts the active run — mid-turn, without waiting for the turn to finish on its own
			HttpResult cancel = request(HttpMethod.DELETE, "/api/v1/chats/" + chatUuid + "/stream", token, null).get(30, TimeUnit.SECONDS);
			assertEquals(204, cancel.statusCode());

			long cancelledAt = System.currentTimeMillis();
			HttpResult firstResult = first.get(60, TimeUnit.SECONDS);
			long elapsed = System.currentTimeMillis() - cancelledAt;
			// The turn only unblocks through cancel(). Falling back to the post-turn check would take the fake's full 30s await instead.
			assertTrue(elapsed < 10_000, "The cancel must interrupt the turn in flight, but the run took " + elapsed + "ms to finish");
			List<SseEvent> events = parseSse(firstResult.body());
			SseEvent agentEnd = events.get(events.size() - 1);
			assertEquals("agent_end", agentEnd.type());
			assertEquals("aborted", agentEnd.data().getString("status"));
		}
	}

}
