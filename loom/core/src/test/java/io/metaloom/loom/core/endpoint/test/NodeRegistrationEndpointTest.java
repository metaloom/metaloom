package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
 * A worker announces a node Loom has never heard of, and it becomes authorable.
 *
 * <p>
 * This is the end-to-end proof of node self-registration, over a real socket against a real Loom:
 * connect, {@code REGISTER}, {@code NODE_REGISTRATION}, read the ack, then fetch the palette over
 * HTTP and find the node there with its ports intact. Every layer the feature touches is on the path —
 * the endpoint, the validation, the registry, the availability join and the REST response — so a
 * regression in any one of them fails here rather than in production.
 * </p>
 *
 * <p>
 * Kept separate from {@code ProcessorEndpointTest} rather than appended to it: that class already runs
 * 14 methods, and the pooled test database has a finite number of provisioned databases per class.
 * </p>
 */
public class NodeRegistrationEndpointTest {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	/**
	 * Held so the client is not garbage collected mid-test — a collected client closes its socket, and
	 * a still-in-use connection then looks disconnected server-side.
	 */
	private final List<WebSocketClient> wsClients = new ArrayList<>();

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	private WebSocket connectWs(Vertx vertx) throws Exception {
		WebSocketClient wsClient = vertx.createWebSocketClient();
		wsClients.add(wsClient);
		CompletableFuture<WebSocket> future = new CompletableFuture<>();
		WebSocketConnectOptions opts = new WebSocketConnectOptions()
			.setPort(restPort())
			.setHost("localhost")
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

	private JsonObject httpGetObject(Vertx vertx, String path, String token) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		client.request(HttpMethod.GET, restPort(), "localhost", path)
			.compose(req -> {
				if (token != null) {
					req.putHeader("Authorization", "Bearer " + token);
				}
				return req.send();
			})
			.compose(resp -> resp.body())
			.onSuccess(body -> {
				try {
					future.complete(new JsonObject(body));
				} catch (Exception e) {
					future.completeExceptionally(e);
				}
			})
			.onFailure(future::completeExceptionally);
		return future.get(10, TimeUnit.SECONDS);
	}

	// ── Fixtures ──────────────────────────────────────────────────────────

	private JsonObject registerMessage(String cortexId) {
		return new JsonObject()
			.put("type", "REGISTER")
			.put("body", new JsonObject()
				.put("nodeId", cortexId)
				.put("name", cortexId)
				.put("host", "10.0.9.9:9090")
				.put("priority", 1)
				.put("capabilities", new JsonArray().add("CPU"))
				.put("nodeWhitelist", new JsonArray().add("acme-nsfw")));
	}

	private JsonObject port(String id, String contentType) {
		return new JsonObject()
			.put("id", id)
			.put("label", id)
			.put("contentType", contentType)
			.put("cardinality", "ONE")
			.put("required", true);
	}

	/** A node Loom has no idea about, using a content type nothing has ever declared. */
	private JsonObject acmeNsfw() {
		return new JsonObject()
			.put("nodeId", "acme-nsfw")
			.put("version", "1.0.0-SNAPSHOT")
			.put("name", "NSFW Classifier")
			.put("description", "Classifies an image against the ACME NSFW taxonomy.")
			.put("icon", "shield")
			.put("category", "ANALYSIS")
			.put("inputPorts", new JsonArray().add(port("media", "media/image")))
			.put("outputPorts", new JsonArray().add(port("result", "struct/nsfw")))
			.put("inputGroups", new JsonArray())
			.put("outputGroups", new JsonArray())
			.put("parameters", new JsonArray())
			.put("events", new JsonArray());
	}

	private JsonObject registrationMessage(String cortexId, JsonObject... nodes) {
		JsonArray array = new JsonArray();
		for (JsonObject node : nodes) {
			array.add(node);
		}
		return new JsonObject()
			.put("type", "NODE_REGISTRATION")
			.put("body", new JsonObject().put("cortexId", cortexId).put("nodes", array));
	}

	// ── Tests ─────────────────────────────────────────────────────────────

	@Test
	public void testAnAnnouncedNodeBecomesAuthorable() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// Loom has never heard of this node.
			assertFalse(paletteContains(vertx, client.getToken(), "acme-nsfw"),
				"the node must not exist before it is announced");

			WebSocket ws = connectWs(vertx);
			assertEquals("REGISTERED", sendAndReceive(ws, registerMessage("cortex-acme")).getString("type"));

			JsonObject ack = sendAndReceive(ws, registrationMessage("cortex-acme", acmeNsfw()));
			assertEquals("NODE_REGISTRATION_ACK", ack.getString("type"));
			assertEquals(new JsonArray().add("acme-nsfw"), ack.getJsonObject("body").getJsonArray("accepted"));
			assertTrue(ack.getJsonObject("body").getJsonArray("rejected").isEmpty());

			// And now it is in the palette, with its ports - which is what the editor draws and what
			// PortGraphAnalyzer validates edges against.
			JsonObject response = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", client.getToken());
			JsonObject descriptor = findDescriptor(response, "acme-nsfw");
			assertNotNull(descriptor, "the announced node must reach the palette");
			assertEquals("NSFW Classifier", descriptor.getString("name"));
			assertEquals("1.0.0-SNAPSHOT", descriptor.getString("version"));
			assertEquals("media/image", descriptor.getJsonArray("inputPorts").getJsonObject(0).getString("contentType"));
			assertEquals("struct/nsfw", descriptor.getJsonArray("outputPorts").getJsonObject(0).getString("contentType"));

			// It is offered by a live worker, so it can actually run.
			JsonObject availability = response.getJsonObject("availability").getJsonObject("acme-nsfw");
			assertEquals("ANNOUNCED", availability.getString("source"));
			assertTrue(availability.getBoolean("available"), "the announcing worker is online");
			assertNull(availability.getJsonArray("providedBy"),
				"the unauthenticated palette response must never name a worker");

			// Worker names come from the secured presence route instead.
			JsonObject secured = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors/availability",
				client.getToken()).getJsonObject("acme-nsfw");
			assertEquals(new JsonArray().add("cortex-acme"), secured.getJsonArray("providedBy"));

			// A content type no Loom build has ever declared is served to the editor with a label.
			JsonObject nsfw = findContentType(response, "struct/nsfw");
			assertNotNull(nsfw, "an announced content type must reach the editor's vocabulary");
			assertEquals("Nsfw", nsfw.getString("label"));

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testTheContractOutlivesTheWorker() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			WebSocket ws = connectWs(vertx);
			sendAndReceive(ws, registerMessage("cortex-leaving"));
			sendAndReceive(ws, registrationMessage("cortex-leaving", acmeNsfw()));
			assertTrue(paletteContains(vertx, client.getToken(), "acme-nsfw"));

			ws.close();
			// Give the close handler a moment to unregister the worker.
			Thread.sleep(500);

			// The whole design in one assertion: the contract is durable, the worker's presence is not.
			// Dropping it here would make a thirty-second rolling restart break every saved pipeline
			// that uses the node.
			JsonObject response = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", client.getToken());
			assertNotNull(findDescriptor(response, "acme-nsfw"),
				"the contract must survive its worker disconnecting");
			assertFalse(response.getJsonObject("availability").getJsonObject("acme-nsfw").getBoolean("available"),
				"but it must be reported as unavailable, so the palette can say so");
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testABuiltInIsNeverShadowedAndTheRefusalIsReported() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			WebSocket ws = connectWs(vertx);
			sendAndReceive(ws, registerMessage("cortex-forger"));

			JsonObject forged = acmeNsfw().put("nodeId", "whisper").put("name", "My Whisper");
			JsonObject ack = sendAndReceive(ws, registrationMessage("cortex-forger", forged));

			JsonArray rejected = ack.getJsonObject("body").getJsonArray("rejected");
			assertEquals(1, rejected.size());
			assertEquals("BUILTIN", rejected.getJsonObject(0).getString("reason"));
			// Reporting it is the point. An author who forks whisper, edits its ports and sees nothing
			// change otherwise has no way to find out why.
			assertNotNull(rejected.getJsonObject(0).getString("message"));

			JsonObject response = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", client.getToken());
			assertFalse("My Whisper".equals(findDescriptor(response, "whisper").getString("name")),
				"Loom's own contract must win");

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testAWorkerMayNotAnnounceForAnotherWorker() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			WebSocket ws = connectWs(vertx);
			sendAndReceive(ws, registerMessage("cortex-honest"));

			JsonObject ack = sendAndReceive(ws, registrationMessage("cortex-somebody-else", acmeNsfw()));

			JsonArray rejected = ack.getJsonObject("body").getJsonArray("rejected");
			assertEquals("ID_MISMATCH", rejected.getJsonObject(0).getString("reason"));
			assertTrue(ack.getJsonObject("body").getJsonArray("accepted").isEmpty());

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testOneMalformedNodeDoesNotCostTheWorkerItsOthers() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			WebSocket ws = connectWs(vertx);
			sendAndReceive(ws, registerMessage("cortex-mixed"));

			JsonObject bad = acmeNsfw()
				.put("nodeId", "acme-bad")
				.put("outputPorts", new JsonArray().add(port("Result Set", "struct/nsfw")));
			JsonObject ack = sendAndReceive(ws, registrationMessage("cortex-mixed", acmeNsfw(), bad));

			assertEquals(new JsonArray().add("acme-nsfw"), ack.getJsonObject("body").getJsonArray("accepted"));
			assertEquals("INVALID_PORT_ID",
				ack.getJsonObject("body").getJsonArray("rejected").getJsonObject(0).getString("reason"));

			assertTrue(paletteContains(vertx, client.getToken(), "acme-nsfw"));
			assertFalse(paletteContains(vertx, client.getToken(), "acme-bad"));

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testARepeatedAnnouncementReplacesRatherThanAccumulates() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			WebSocket ws = connectWs(vertx);
			sendAndReceive(ws, registerMessage("cortex-shrinking"));

			JsonObject second = acmeNsfw().put("nodeId", "acme-extra").put("name", "Extra");
			sendAndReceive(ws, registrationMessage("cortex-shrinking", acmeNsfw(), second));
			assertTrue(paletteContains(vertx, client.getToken(), "acme-extra"));

			// A worker that drops a node announces a shorter list. There is no delta frame, so the
			// dropped node must not linger.
			sendAndReceive(ws, registrationMessage("cortex-shrinking", acmeNsfw()));

			assertTrue(paletteContains(vertx, client.getToken(), "acme-nsfw"));
			assertFalse(paletteContains(vertx, client.getToken(), "acme-extra"),
				"a node absent from a later announcement must be unlinked");

			ws.close();
		} finally {
			vertx.close();
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	private boolean paletteContains(Vertx vertx, String token, String nodeId) throws Exception {
		return findDescriptor(httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", token), nodeId) != null;
	}

	private JsonObject findDescriptor(JsonObject response, String nodeId) {
		JsonArray descriptors = response.getJsonArray("nodeDescriptors");
		for (int i = 0; i < descriptors.size(); i++) {
			if (nodeId.equals(descriptors.getJsonObject(i).getString("nodeId"))) {
				return descriptors.getJsonObject(i);
			}
		}
		return null;
	}

	private JsonObject findContentType(JsonObject response, String id) {
		JsonArray types = response.getJsonArray("contentTypes");
		for (int i = 0; i < types.size(); i++) {
			if (id.equals(types.getJsonObject(i).getString("id"))) {
				return types.getJsonObject(i);
			}
		}
		return null;
	}
}
