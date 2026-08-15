package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * and for the handling of PIPELINE_EVENT frames arriving on the processor WebSocket.
 *
 * <p>Under Variant C, Loom owns the pipeline graph, so every event describing a run is
 * Loom's to emit — {@code RunStatsAggregator} counts node settles and pushes
 * {@code NODE_STATS} on a timer, releasing only failures immediately. A worker frame
 * that reached the broadcaster went straight past that aggregation, which is the flood
 * the aggregator exists to prevent. These tests therefore assert the opposite of what
 * they once did: a PIPELINE_EVENT from a processor is <em>dropped</em>, not forwarded.</p>
 *
 * <p>Fan-out to several subscribers, which used to be covered incidentally through this
 * passthrough, is covered directly in {@code PipelineEventBroadcasterTest}.</p>
 */
public class PipelineEventEndpointTest {

	private static final Logger log = LoggerFactory.getLogger(PipelineEventEndpointTest.class);

	/**
	 * How long to wait before concluding that no frame is coming. Generous enough that a
	 * slow broadcast would still be caught rather than passing as a drop.
	 */
	private static final long QUIET_WINDOW_MS = 2000;

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

	/**
	 * The pipeline events socket is multiplexed over four channels: {@code PROCESSOR}
	 * lifecycle frames, {@code NODE_REGISTRY} descriptor/availability frames,
	 * {@code NOTIFICATION} frames, and pipeline frames. Pipeline frames are the ones
	 * carrying <em>no</em> {@code channel} field at all, which is how a UI client tells
	 * them apart (see {@code loom-ui/src/api/pipelineEvents.ts}).
	 *
	 * <p>Testing for the absence of the field rather than for "not PROCESSOR": the
	 * negative form counted a NODE_REGISTRY frame as a pipeline frame, and registering
	 * the test processor is itself what triggers a NODE_AVAILABILITY_CHANGED broadcast.
	 * Whether that landed inside the quiet window was a matter of timing, so the drop
	 * assertion below failed intermittently against a socket behaving correctly.</p>
	 */
	private static boolean isPipelineFrame(JsonObject frame) {
		return frame.getString("channel") == null;
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

	/**
	 * Assert that nothing pipeline-shaped arrives on {@code uiWs} within the quiet
	 * window. Collects rather than short-circuits so a failure can name what leaked.
	 */
	private void assertNoPipelineFrame(WebSocket uiWs) throws Exception {
		CopyOnWriteArrayList<JsonObject> leaked = new CopyOnWriteArrayList<>();
		CompletableFuture<JsonObject> first = new CompletableFuture<>();
		uiWs.textMessageHandler(text -> {
			JsonObject frame = new JsonObject(text);
			if (!isPipelineFrame(frame)) {
				return;
			}
			leaked.add(frame);
			first.complete(frame);
		});
		try {
			JsonObject frame = first.get(QUIET_WINDOW_MS, TimeUnit.MILLISECONDS);
			throw new AssertionError("A processor-sent PIPELINE_EVENT reached a UI subscriber, bypassing the run"
				+ " aggregator: " + frame.encode());
		} catch (TimeoutException expected) {
			assertTrue(leaked.isEmpty(), "Expected no pipeline frames but got " + leaked);
			log.info("No pipeline frame reached the subscriber within {}ms, as expected", QUIET_WINDOW_MS);
		}
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

	/**
	 * A per-item node event from a worker is the exact frame the aggregator replaces
	 * with a counter, so it must not reach a subscriber.
	 */
	@Test
	public void testProcessorPipelineEventIsDropped() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket uiWs = connectPipelineEventsWs(vertx);
			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-1"));

			processorWs.writeTextMessage(pipelineEventMessage("my-pipeline", "sha512", "NODE_STARTED").encode());

			assertNoPipelineFrame(uiWs);

			uiWs.close();
			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	/**
	 * A whole run lifecycle from a worker is dropped, not only the noisiest member of
	 * it — the run's lifecycle is Loom's to report because Loom holds the graph.
	 */
	@Test
	public void testProcessorLifecycleSequenceIsDropped() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket uiWs = connectPipelineEventsWs(vertx);
			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-lifecycle"));

			String pipeline = "video-pipeline";
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, null, "PIPELINE_STARTED").encode());
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, "sha512", "NODE_STARTED").encode());
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, "sha512", "NODE_COMPLETED").encode());
			processorWs.writeTextMessage(pipelineEventMessage(pipeline, null, "PIPELINE_COMPLETED").encode());

			assertNoPipelineFrame(uiWs);

			uiWs.close();
			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	/**
	 * NODE_STATS is the aggregator's own output type. A worker sending one would forge
	 * a snapshot Loom never computed, so it is dropped like the rest.
	 */
	@Test
	public void testProcessorNodeStatsIsDropped() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket uiWs = connectPipelineEventsWs(vertx);
			WebSocket processorWs = connectProcessorWs(vertx);
			sendAndReceive(processorWs, registerMessage("proc-ev-stats"));

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

			assertNoPipelineFrame(uiWs);

			uiWs.close();
			processorWs.close();
		} finally {
			vertx.close();
		}
	}

	/**
	 * Dropping the payload does not weaken the envelope checks: an unregistered sender
	 * is still refused, so the drop cannot be mistaken for an accepted frame.
	 */
	@Test
	public void testPipelineEventWithoutRegister() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket processorWs = connectProcessorWs(vertx);

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

			JsonObject resp = sendAndReceive(processorWs,
				new JsonObject().put("type", "PIPELINE_EVENT"));
			assertEquals("ERROR", resp.getString("type"));

			processorWs.close();
		} finally {
			vertx.close();
		}
	}
}
