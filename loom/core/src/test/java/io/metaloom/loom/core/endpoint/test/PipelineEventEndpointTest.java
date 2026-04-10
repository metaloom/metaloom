package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for the pipeline events WebSocket endpoint ({@code /api/v1/pipelines/events/ws})
 * and for the PIPELINE_EVENT forwarding path through the processor WebSocket.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>A UI client can connect to the pipeline events WebSocket</li>
 *   <li>When a processor sends a PIPELINE_EVENT message, it is broadcast to all
 *       connected UI subscribers</li>
 *   <li>Multiple subscribers receive the same event</li>
 *   <li>The event payload is preserved end-to-end</li>
 * </ul>
 */
public class PipelineEventEndpointTest {

	private static final Logger log = LoggerFactory.getLogger(PipelineEventEndpointTest.class);

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	// ── WebSocket helpers ─────────────────────────────────────────────────

	private WebSocket connectPipelineEventsWs(Vertx vertx) throws Exception {
		WebSocketClient wsClient = vertx.createWebSocketClient();
		CompletableFuture<WebSocket> future = new CompletableFuture<>();
		WebSocketConnectOptions opts = new WebSocketConnectOptions()
			.setHost("localhost")
			.setPort(restPort())
			.setURI("/api/v1/pipelines/events/ws");
		wsClient.connect(opts)
			.onSuccess(future::complete)
			.onFailure(future::completeExceptionally);
		return future.get(10, TimeUnit.SECONDS);
	}

	private WebSocket connectProcessorWs(Vertx vertx) throws Exception {
		WebSocketClient wsClient = vertx.createWebSocketClient();
		CompletableFuture<WebSocket> future = new CompletableFuture<>();
		WebSocketConnectOptions opts = new WebSocketConnectOptions()
			.setHost("localhost")
			.setPort(restPort())
			.setURI("/api/v1/processors/ws");
		wsClient.connect(opts)
			.onSuccess(future::complete)
			.onFailure(future::completeExceptionally);
		return future.get(10, TimeUnit.SECONDS);
	}

	private JsonObject sendAndReceive(WebSocket ws, JsonObject message) throws Exception {
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		ws.textMessageHandler(text -> future.complete(new JsonObject(text)));
		ws.writeTextMessage(message.encode());
		return future.get(10, TimeUnit.SECONDS);
	}

	private JsonObject registerMessage(String nodeId) {
		return new JsonObject()
			.put("type", "REGISTER")
			.put("body", new JsonObject()
				.put("nodeId", nodeId)
				.put("name", "cortex-test-" + nodeId)
				.put("host", "localhost:9090")
				.put("priority", 1)
				.put("capabilities", new JsonArray().add("CPU")));
	}

	private JsonObject pipelineEventMessage(String pipelineName, String nodeId, String type) {
		return new JsonObject()
			.put("type", "PIPELINE_EVENT")
			.put("body", new JsonObject()
				.put("type", type)
				.put("pipelineName", pipelineName)
				.put("nodeId", nodeId)
				.put("mediaPath", "/data/media/test.mp4")
				.put("timestamp", System.currentTimeMillis()));
	}

	// ── Tests ─────────────────────────────────────────────────────────────

	@Test
	public void testConnectToPipelineEventsWs() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectPipelineEventsWs(vertx);
			assertNotNull(ws);
			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testPipelineEventForwarding() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			// 1. Connect a UI subscriber to the pipeline events WebSocket
			WebSocket uiWs = connectPipelineEventsWs(vertx);
			CompletableFuture<JsonObject> receivedEvent = new CompletableFuture<>();
			uiWs.textMessageHandler(text -> receivedEvent.complete(new JsonObject(text)));

			// 2. Connect a processor and register it
			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-1"));

			// 3. Send a PIPELINE_EVENT from the processor
			processorWs.writeTextMessage(pipelineEventMessage("my-pipeline", "sha512", "NODE_STARTED").encode());

			// 4. Verify the UI subscriber receives the event
			JsonObject event = receivedEvent.get(10, TimeUnit.SECONDS);
			assertNotNull(event);
			assertEquals("NODE_STARTED", event.getString("type"));
			assertEquals("my-pipeline", event.getString("pipelineName"));
			assertEquals("sha512", event.getString("nodeId"));
			assertEquals("/data/media/test.mp4", event.getString("mediaPath"));

			uiWs.close();
			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testMultipleSubscribersReceiveEvent() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			// Connect two UI subscribers
			WebSocket uiWs1 = connectPipelineEventsWs(vertx);
			WebSocket uiWs2 = connectPipelineEventsWs(vertx);
			CopyOnWriteArrayList<JsonObject> received1 = new CopyOnWriteArrayList<>();
			CopyOnWriteArrayList<JsonObject> received2 = new CopyOnWriteArrayList<>();
			CompletableFuture<Void> got1 = new CompletableFuture<>();
			CompletableFuture<Void> got2 = new CompletableFuture<>();
			uiWs1.textMessageHandler(text -> {
				received1.add(new JsonObject(text));
				got1.complete(null);
			});
			uiWs2.textMessageHandler(text -> {
				received2.add(new JsonObject(text));
				got2.complete(null);
			});

			// Connect and register a processor
			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-multi"));

			// Send event
			processorWs.writeTextMessage(
				pipelineEventMessage("my-pipeline", "tika", "NODE_COMPLETED").encode());

			// Wait for both to receive
			got1.get(10, TimeUnit.SECONDS);
			got2.get(10, TimeUnit.SECONDS);

			assertEquals(1, received1.size());
			assertEquals(1, received2.size());
			assertEquals("NODE_COMPLETED", received1.get(0).getString("type"));
			assertEquals("NODE_COMPLETED", received2.get(0).getString("type"));

			uiWs1.close();
			uiWs2.close();
			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testPipelineEventWithoutRegister() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket processorWs = connectProcessorWs(vertx);

			// Try sending PIPELINE_EVENT without registering first
			JsonObject resp = sendAndReceive(processorWs,
				pipelineEventMessage("my-pipeline", "sha512", "NODE_STARTED"));
			assertEquals("ERROR", resp.getString("type"));

			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testPipelineEventWithoutBody() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-nobody"));

			// Send PIPELINE_EVENT without body
			JsonObject resp = sendAndReceive(processorWs,
				new JsonObject().put("type", "PIPELINE_EVENT"));
			assertEquals("ERROR", resp.getString("type"));

			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testNodeLifecycleEventSequence() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			// Connect UI subscriber
			WebSocket uiWs = connectPipelineEventsWs(vertx);
			CopyOnWriteArrayList<JsonObject> received = new CopyOnWriteArrayList<>();
			CompletableFuture<Void> gotAll = new CompletableFuture<>();
			uiWs.textMessageHandler(text -> {
				received.add(new JsonObject(text));
				if (received.size() >= 4) {
					gotAll.complete(null);
				}
			});

			// Connect and register processor
			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-lifecycle"));

			// Send a full lifecycle sequence
			String pipeline = "video-pipeline";
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, null, "PIPELINE_STARTED").encode());
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, "sha512", "NODE_STARTED").encode());
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, "sha512", "NODE_COMPLETED").encode());
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, null, "PIPELINE_COMPLETED").encode());

			gotAll.get(10, TimeUnit.SECONDS);

			assertEquals(4, received.size());
			assertEquals("PIPELINE_STARTED", received.get(0).getString("type"));
			assertEquals("NODE_STARTED", received.get(1).getString("type"));
			assertEquals("NODE_COMPLETED", received.get(2).getString("type"));
			assertEquals("PIPELINE_COMPLETED", received.get(3).getString("type"));

			// All events reference the same pipeline
			for (JsonObject ev : received) {
				assertEquals(pipeline, ev.getString("pipelineName"));
			}

			uiWs.close();
			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testNodeStatsEvent() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket uiWs = connectPipelineEventsWs(vertx);
			CompletableFuture<JsonObject> receivedEvent = new CompletableFuture<>();
			uiWs.textMessageHandler(text -> receivedEvent.complete(new JsonObject(text)));

			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-stats"));

			// Send NODE_STATS event with volume data
			JsonObject statsEvent = new JsonObject()
				.put("type", "PIPELINE_EVENT")
				.put("body", new JsonObject()
					.put("type", "NODE_STATS")
					.put("pipelineName", "my-pipeline")
					.put("nodeId", "sha512")
					.put("activeCount", 3)
					.put("pendingCount", 12)
					.put("processedCount", 1042)
					.put("failedCount", 2)
					.put("timestamp", System.currentTimeMillis()));
			processorWs.writeTextMessage(statsEvent.encode());

			JsonObject event = receivedEvent.get(10, TimeUnit.SECONDS);
			assertEquals("NODE_STATS", event.getString("type"));
			assertEquals("sha512", event.getString("nodeId"));
			assertEquals(3, event.getInteger("activeCount"));
			assertEquals(12, event.getInteger("pendingCount"));
			assertEquals(1042L, event.getLong("processedCount"));
			assertEquals(2L, event.getLong("failedCount"));

			uiWs.close();
			processorWs.close();
		} finally {
			vertx.close();
		}
	}
}
