package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for the {@code /api/v1/processors} endpoint, including the WebSocket protocol
 * used by cortex (processor) nodes to register, send heartbeats, report status,
 * and change state.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>A processor can register via WebSocket and receives a REGISTERED ack</li>
 *   <li>Heartbeat messages are acknowledged</li>
 *   <li>Status updates are stored and reflected in the REST list endpoint</li>
 *   <li>State changes (PAUSED, ONLINE) are reflected in the REST responses</li>
 *   <li>The REST response contains all fields needed by the Cortex UI view
 *       (uuid, name, host, priority, state, capabilities, systemStatus with
 *        cpuLoad, gpuLoad, ioLoad, memoryUsed, memoryTotal, diskUsed, diskTotal, lastSeen)</li>
 * </ul>
 */
public class ProcessorEndpointTest {

	private static final Logger log = LoggerFactory.getLogger(ProcessorEndpointTest.class);

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	// ── WebSocket helpers ─────────────────────────────────────────────────

	/**
	 * Retains the per-connection {@link WebSocketClient}s so they are not garbage collected
	 * mid-test. A dropped client gets cleaned up and closes its socket, which would make a
	 * still-in-use connection look disconnected server-side - fatal for tests that keep two
	 * sockets open at once (e.g. duplicate-registration).
	 */
	private final java.util.List<WebSocketClient> wsClients = new java.util.ArrayList<>();

	private WebSocket connectWs(Vertx vertx) throws Exception {
		WebSocketClient wsClient = vertx.createWebSocketClient();
		wsClients.add(wsClient);
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

	private JsonObject registerMessage(String nodeId, String name, String host, int priority, String... capabilities) {
		JsonArray caps = new JsonArray();
		for (String c : capabilities) {
			caps.add(c);
		}
		return new JsonObject()
			.put("type", "REGISTER")
			.put("body", new JsonObject()
				.put("nodeId", nodeId)
				.put("name", name)
				.put("host", host)
				.put("priority", priority)
				.put("capabilities", caps));
	}

	// ── Tests ─────────────────────────────────────────────────────────────

	@Test
	public void testRegister() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Send REGISTER
			JsonObject resp = sendAndReceive(ws,
				registerMessage("node-1", "cortex-gpu-01", "10.0.1.10:9090", 1, "GPU", "CPU"));

			assertEquals("REGISTERED", resp.getString("type"));
			JsonObject body = resp.getJsonObject("body");
			assertNotNull(body);
			assertNotNull(body.getString("uuid"));
			assertEquals("cortex-gpu-01", body.getString("name"));
			assertEquals("10.0.1.10:9090", body.getString("host"));
			assertEquals(1, body.getInteger("priority"));
			assertEquals("ONLINE", body.getString("state"));

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testRegisterDuplicateNodeIdRejected() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			// First worker registers successfully under the shared id.
			WebSocket ws1 = connectWs(vertx);
			JsonObject firstResp = sendAndReceive(ws1,
				registerMessage("dup-node", "cortex-dup-a", "10.0.3.10:9090", 1, "CPU"));
			assertEquals("REGISTERED", firstResp.getString("type"));

			// A second, still-live worker announcing the same id must be rejected rather than
			// silently replacing the first.
			WebSocket ws2 = connectWs(vertx);
			JsonObject secondResp = sendAndReceive(ws2,
				registerMessage("dup-node", "cortex-dup-b", "10.0.3.11:9090", 1, "CPU"));
			assertEquals("ERROR", secondResp.getString("type"));
			assertTrue(secondResp.getJsonObject("body").getString("message").contains("already connected"),
				"Rejection message should explain the id is already connected");

			// The first worker is still the one Loom knows for that id.
			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				JsonObject listResp = httpGet(vertx, "/api/v1/processors", client.getToken());
				JsonObject processor = findByNodeId(listResp.getJsonArray("data"), "dup-node");
				assertNotNull(processor, "The originally registered worker must remain");
				assertEquals("cortex-dup-a", processor.getString("name"),
					"The duplicate must not have replaced the live worker");
			}

			ws1.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testHeartbeat() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Register first
			sendAndReceive(ws, registerMessage("node-hb", "cortex-hb", "10.0.1.20:9090", 2, "CPU"));

			// Send HEARTBEAT
			JsonObject hbResp = sendAndReceive(ws, new JsonObject().put("type", "HEARTBEAT"));
			assertEquals("HEARTBEAT_ACK", hbResp.getString("type"));

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testHeartbeatWithoutRegister() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Send HEARTBEAT without registering first
			JsonObject resp = sendAndReceive(ws, new JsonObject().put("type", "HEARTBEAT"));
			assertEquals("ERROR", resp.getString("type"));

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testStatusUpdate() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Register
			sendAndReceive(ws, registerMessage("node-status", "cortex-status-01", "10.0.1.30:9090", 3, "GPU", "CPU", "IO"));

			// Send STATUS_UPDATE — no ack expected, so we'll query REST afterwards
			JsonObject statusMsg = new JsonObject()
				.put("type", "STATUS_UPDATE")
				.put("body", new JsonObject()
					.put("cpuLoad", 42.5)
					.put("gpuLoad", 78.0)
					.put("ioLoad", 31.0)
					.put("memoryUsed", 4_294_967_296L)
					.put("memoryTotal", 17_179_869_184L)
					.put("diskUsed", 107_374_182_400L)
					.put("diskTotal", 536_870_912_000L));
			ws.writeTextMessage(statusMsg.encode());

			// Give the server a moment to process
			Thread.sleep(500);

			// Verify via REST list endpoint
			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				JsonObject listResp = httpGet(vertx, "/api/v1/processors", client.getToken());

				log.info("Processor list response: {}", listResp.encodePrettily());

				JsonArray data = listResp.getJsonArray("data");
				assertNotNull(data, "Expected 'data' array in list response");
				assertTrue(data.size() >= 1, "Expected at least one processor in the list");

				// Find our processor
				JsonObject processor = findByName(data, "cortex-status-01");
				assertNotNull(processor, "Expected to find cortex-status-01 in list");
				assertEquals("ONLINE", processor.getString("state"));
				assertEquals("10.0.1.30:9090", processor.getString("host"));
				assertEquals(3, processor.getInteger("priority"));

				// Verify capabilities
				JsonArray caps = processor.getJsonArray("capabilities");
				assertNotNull(caps);
				assertTrue(caps.contains("GPU"));
				assertTrue(caps.contains("CPU"));
				assertTrue(caps.contains("IO"));

				// Verify system status
				JsonObject sysStatus = processor.getJsonObject("systemStatus");
				assertNotNull(sysStatus, "Expected systemStatus in processor response");
				assertEquals(42.5, sysStatus.getDouble("cpuLoad"), 0.01);
				assertEquals(78.0, sysStatus.getDouble("gpuLoad"), 0.01);
				assertEquals(31.0, sysStatus.getDouble("ioLoad"), 0.01);
				assertEquals(4_294_967_296L, sysStatus.getLong("memoryUsed"));
				assertEquals(17_179_869_184L, sysStatus.getLong("memoryTotal"));
				assertEquals(107_374_182_400L, sysStatus.getLong("diskUsed"));
				assertEquals(536_870_912_000L, sysStatus.getLong("diskTotal"));

				// Verify lastSeen is set
				assertNotNull(processor.getString("lastSeen"), "Expected lastSeen timestamp");

				// Verify uuid is set
				assertNotNull(processor.getString("uuid"), "Expected uuid");
			}

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testStateChange() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Register
			sendAndReceive(ws, registerMessage("node-state", "cortex-state-01", "10.0.1.40:9090", 5, "CPU", "IO"));

			// Change state to PAUSED
			ws.writeTextMessage(new JsonObject()
				.put("type", "STATE_CHANGE")
				.put("body", new JsonObject().put("state", "PAUSED"))
				.encode());

			Thread.sleep(500);

			// Verify via REST
			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				JsonObject listResp = httpGet(vertx, "/api/v1/processors", client.getToken());
				JsonObject processor = findByName(listResp.getJsonArray("data"), "cortex-state-01");
				assertNotNull(processor);
				assertEquals("PAUSED", processor.getString("state"));
			}

			// Change back to ONLINE
			ws.writeTextMessage(new JsonObject()
				.put("type", "STATE_CHANGE")
				.put("body", new JsonObject().put("state", "ONLINE"))
				.encode());

			Thread.sleep(500);

			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				JsonObject listResp = httpGet(vertx, "/api/v1/processors", client.getToken());
				JsonObject processor = findByName(listResp.getJsonArray("data"), "cortex-state-01");
				assertNotNull(processor);
				assertEquals("ONLINE", processor.getString("state"));
			}

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testReadSingleProcessor() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Register
			JsonObject registerResp = sendAndReceive(ws,
				registerMessage("node-single", "cortex-single-01", "10.0.1.50:9090", 7, "GPU"));

			String uuid = registerResp.getJsonObject("body").getString("uuid");
			assertNotNull(uuid);

			// Read single processor via REST
			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				JsonObject resp = httpGet(vertx, "/api/v1/processors/" + uuid, client.getToken());
				assertNotNull(resp);
				assertEquals("cortex-single-01", resp.getString("name"));
				assertEquals(uuid, resp.getString("uuid"));
				assertEquals("10.0.1.50:9090", resp.getString("host"));
				assertEquals(7, resp.getInteger("priority"));
				assertEquals("ONLINE", resp.getString("state"));
			}

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testInvalidMessage() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Send message without type
			JsonObject resp = sendAndReceive(ws, new JsonObject().put("body", new JsonObject()));
			assertEquals("ERROR", resp.getString("type"));

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testRegisterWithoutBody() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Send REGISTER without body
			JsonObject resp = sendAndReceive(ws, new JsonObject().put("type", "REGISTER"));
			assertEquals("ERROR", resp.getString("type"));

			ws.close();
		} finally {
			vertx.close();
		}
	}

	/**
	 * Verify that the REST response contains all fields required by the CortexView UI.
	 * The CortexView WorkerNode interface needs:
	 * <ul>
	 *   <li>id (uuid)</li>
	 *   <li>name</li>
	 *   <li>host</li>
	 *   <li>priority</li>
	 *   <li>status (state)</li>
	 *   <li>stats.cpu (systemStatus.cpuLoad)</li>
	 *   <li>stats.gpu (systemStatus.gpuLoad)</li>
	 *   <li>stats.io (systemStatus.ioLoad)</li>
	 *   <li>stats.memory (systemStatus.memoryUsed + memoryTotal)</li>
	 *   <li>capabilities</li>
	 * </ul>
	 */
	@Test
	public void testCortexViewDataCompleteness() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);

			// Register with full capabilities
			sendAndReceive(ws,
				registerMessage("node-ui-check", "cortex-gpu-ui", "10.0.1.60:9090", 1, "GPU", "CPU", "IO"));

			// Send full status update
			ws.writeTextMessage(new JsonObject()
				.put("type", "STATUS_UPDATE")
				.put("body", new JsonObject()
					.put("cpuLoad", 42.0)
					.put("gpuLoad", 78.0)
					.put("ioLoad", 31.0)
					.put("memoryUsed", 4_294_967_296L)
					.put("memoryTotal", 17_179_869_184L)
					.put("diskUsed", 107_374_182_400L)
					.put("diskTotal", 536_870_912_000L))
				.encode());

			Thread.sleep(500);

			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				JsonObject listResp = httpGet(vertx, "/api/v1/processors", client.getToken());
				JsonObject p = findByName(listResp.getJsonArray("data"), "cortex-gpu-ui");
				assertNotNull(p, "Processor not found in list");

				// Fields needed by CortexView WorkerNode
				assertNotNull(p.getString("uuid"), "uuid is required for CortexView id");
				assertNotNull(p.getString("name"), "name is required for CortexView name");
				assertNotNull(p.getString("host"), "host is required for CortexView host");
				assertNotNull(p.getInteger("priority"), "priority is required for CortexView priority");
				assertNotNull(p.getString("state"), "state is required for CortexView status");
				assertNotNull(p.getString("lastSeen"), "lastSeen is required for CortexView");

				// Capabilities
				JsonArray caps = p.getJsonArray("capabilities");
				assertNotNull(caps, "capabilities is required for CortexView");
				assertEquals(3, caps.size(), "Expected 3 capabilities (GPU, CPU, IO)");

				// System status — maps to CortexView stats
				JsonObject ss = p.getJsonObject("systemStatus");
				assertNotNull(ss, "systemStatus is required for CortexView stats");
				assertNotNull(ss.getDouble("cpuLoad"), "cpuLoad is required for CortexView stats.cpu");
				assertNotNull(ss.getDouble("gpuLoad"), "gpuLoad is required for CortexView stats.gpu");
				assertNotNull(ss.getDouble("ioLoad"), "ioLoad is required for CortexView stats.io");
				assertNotNull(ss.getLong("memoryUsed"), "memoryUsed is required for CortexView stats.memory");
				assertNotNull(ss.getLong("memoryTotal"), "memoryTotal is required for CortexView stats.memory percentage");

				log.info("All CortexView fields present in processor response: {}", p.encodePrettily());
			}

			ws.close();
		} finally {
			vertx.close();
		}
	}

	// ── Node-restriction tests (Task 2/4) ─────────────────────────────────

	@Test
	public void testUpdateAndReadRestrictions() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);
			sendAndReceive(ws, registerMessage("node-restrict", "cortex-restrict-01", "10.0.2.10:9090", 1, "GPU", "CPU"));

			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				String token = client.getToken();

				JsonObject body = new JsonObject()
					.put("nodeWhitelist", new JsonArray().add("sha256").add("fingerprint"))
					.put("nodeBlacklist", new JsonArray().add("loom"));
				int[] status = new int[1];
				JsonObject putResp = httpSend(vertx, HttpMethod.PUT, "/api/v1/processors/node-restrict/restrictions", token, body, status);
				assertEquals(200, status[0], "PUT /restrictions should succeed for an admin");
				assertTrue(putResp.getJsonArray("nodeWhitelist").contains("sha256"));
				assertTrue(putResp.getJsonArray("nodeBlacklist").contains("loom"));

				// The persisted restriction round-trips on a subsequent read.
				JsonObject getResp = httpGet(vertx, "/api/v1/processors/node-restrict", token);
				assertTrue(getResp.getJsonArray("nodeWhitelist").contains("sha256"));
				assertTrue(getResp.getJsonArray("nodeBlacklist").contains("loom"));
				assertEquals(Boolean.TRUE, getResp.getBoolean("persisted"), "A DB-backed worker is persisted");
			}

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testListMergesPersistedOffline() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				String token = client.getToken();

				// PUT for a nodeId that never connected creates a persisted-but-offline instance.
				JsonObject body = new JsonObject()
					.put("nodeWhitelist", new JsonArray().add("whisper"))
					.put("nodeBlacklist", new JsonArray());
				int[] status = new int[1];
				httpSend(vertx, HttpMethod.PUT, "/api/v1/processors/ghost-node/restrictions", token, body, status);
				assertEquals(200, status[0]);

				JsonObject listResp = httpGet(vertx, "/api/v1/processors", token);
				JsonObject ghost = findByNodeId(listResp.getJsonArray("data"), "ghost-node");
				assertNotNull(ghost, "The offline persisted worker must still appear in the list");
				assertEquals("OFFLINE", ghost.getString("state"));
				assertEquals(Boolean.TRUE, ghost.getBoolean("persisted"));
				assertTrue(ghost.getJsonArray("nodeWhitelist").contains("whisper"));
			}
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testForgetOnlyWhenOffline() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);
			sendAndReceive(ws, registerMessage("node-del", "cortex-del-01", "10.0.2.20:9090", 1, "CPU"));

			try (LoomHttpClient client = loom.httpClient()) {
				loginAdmin(client);
				String token = client.getToken();

				// Ensure a persisted row exists.
				JsonObject body = new JsonObject().put("nodeWhitelist", new JsonArray().add("sha256")).put("nodeBlacklist", new JsonArray());
				int[] status = new int[1];
				httpSend(vertx, HttpMethod.PUT, "/api/v1/processors/node-del/restrictions", token, body, status);
				assertEquals(200, status[0]);

				// Online worker cannot be forgotten.
				httpSend(vertx, HttpMethod.DELETE, "/api/v1/processors/node-del", token, null, status);
				assertEquals(409, status[0], "Forgetting an online worker must be rejected");

				// Disconnect, then forget.
				ws.close();
				Thread.sleep(500);
				httpSend(vertx, HttpMethod.DELETE, "/api/v1/processors/node-del", token, null, status);
				assertEquals(204, status[0], "An offline persisted worker can be forgotten");

				// It is gone.
				httpSend(vertx, HttpMethod.GET, "/api/v1/processors/node-del", token, null, status);
				assertEquals(404, status[0]);
			}
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testRestrictionsRequireAuthentication() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			int[] status = new int[1];
			JsonObject body = new JsonObject().put("nodeWhitelist", new JsonArray()).put("nodeBlacklist", new JsonArray());
			// No token → the secure() auth handler rejects before the handler runs.
			httpSend(vertx, HttpMethod.PUT, "/api/v1/processors/node-x/restrictions", null, body, status);
			assertEquals(401, status[0], "PUT /restrictions without a token must be unauthorized");
		} finally {
			vertx.close();
		}
	}

	// ── HTTP helpers ──────────────────────────────────────────────────────

	/**
	 * Perform a raw HTTP request, capturing the status code in {@code statusOut[0]} and
	 * returning the JSON body (empty object for a 204/no-content response).
	 */
	private JsonObject httpSend(Vertx vertx, HttpMethod method, String path, String token, JsonObject body, int[] statusOut) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		client.request(method, restPort(), "localhost", path)
			.compose(req -> {
				if (token != null) {
					req.putHeader("Authorization", "Bearer " + token);
				}
				if (body != null) {
					req.putHeader("Content-Type", "application/json");
					return req.send(body.encode());
				}
				return req.send();
			})
			.compose(resp -> {
				statusOut[0] = resp.statusCode();
				return resp.body();
			})
			.onSuccess(buf -> {
				// Error responses (401 "Unauthorized", 404/409 JSON) may not be JSON objects;
				// the caller asserts on the status code, so a non-JSON body is not a failure.
				try {
					future.complete(buf == null || buf.length() == 0 ? new JsonObject() : new JsonObject(buf));
				} catch (Exception e) {
					future.complete(new JsonObject());
				}
			})
			.onFailure(future::completeExceptionally);

		return future.get(10, TimeUnit.SECONDS);
	}

	private JsonObject findByNodeId(JsonArray data, String nodeId) {
		if (data == null) {
			return null;
		}
		for (int i = 0; i < data.size(); i++) {
			JsonObject obj = data.getJsonObject(i);
			if (nodeId.equals(obj.getString("nodeId"))) {
				return obj;
			}
		}
		return null;
	}

	/**
	 * Perform a raw HTTP GET and return the response body as JsonObject.
	 */
	private JsonObject httpGet(Vertx vertx, String path, String token) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		client.request(io.vertx.core.http.HttpMethod.GET, restPort(), "localhost", path)
			.compose(req -> {
				req.putHeader("Authorization", "Bearer " + token);
				return req.send();
			})
			.compose(resp -> resp.body())
			.onSuccess(body -> future.complete(new JsonObject(body)))
			.onFailure(future::completeExceptionally);

		return future.get(10, TimeUnit.SECONDS);
	}

	private JsonObject findByName(JsonArray data, String name) {
		if (data == null) {
			return null;
		}
		for (int i = 0; i < data.size(); i++) {
			JsonObject obj = data.getJsonObject(i);
			if (name.equals(obj.getString("name"))) {
				return obj;
			}
		}
		return null;
	}
}
