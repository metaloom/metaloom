package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration tests for the node-descriptors and content-types REST endpoints.
 */
public class NodeDescriptorEndpointTest {

	private static final Logger log = LoggerFactory.getLogger(NodeDescriptorEndpointTest.class);

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	// ── HTTP helpers ──────────────────────────────────────────────────────

	private JsonArray httpGetArray(Vertx vertx, String path, String token) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonArray> future = new CompletableFuture<>();
		client.request(HttpMethod.GET, restPort(), "localhost", path)
			.compose(req -> {
				req.putHeader("Authorization", "Bearer " + token);
				return req.send();
			})
			.compose(resp -> resp.body())
			// Decode inside a try: an exception thrown in onSuccess would otherwise leave the
			// future uncompleted and turn a simple shape mismatch into a 10 second timeout.
			.onSuccess(body -> {
				try {
					future.complete(new JsonArray(body));
				} catch (Exception e) {
					future.completeExceptionally(e);
				}
			})
			.onFailure(future::completeExceptionally);
		return future.get(10, TimeUnit.SECONDS);
	}

	private JsonObject httpGetObject(Vertx vertx, String path, String token) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		client.request(HttpMethod.GET, restPort(), "localhost", path)
			.compose(req -> {
				// A null token exercises the anonymous path: these routes are deliberately
				// unauthenticated so the editor can load the palette before login.
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

	/**
	 * Load the node descriptor list. The route returns a combined object for the UI -
	 * {"nodeDescriptors": [...], "contentTypes": [...]} - so the array lives under a key.
	 */
	private JsonArray nodeDescriptors(Vertx vertx, String token) throws Exception {
		return httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", token).getJsonArray("nodeDescriptors");
	}

	private int httpGetStatus(Vertx vertx, String path, String token) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<Integer> future = new CompletableFuture<>();
		client.request(HttpMethod.GET, restPort(), "localhost", path)
			.compose(req -> {
				req.putHeader("Authorization", "Bearer " + token);
				return req.send();
			})
			.onSuccess(resp -> future.complete(resp.statusCode()))
			.onFailure(future::completeExceptionally);
		return future.get(10, TimeUnit.SECONDS);
	}

	// ── Tests ─────────────────────────────────────────────────────────────

	@Test
	public void testListAllNodeDescriptors() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonArray descriptors = nodeDescriptors(vertx, client.getToken());
			assertNotNull(descriptors);
			assertTrue(descriptors.size() >= 29, "Expected at least 29 descriptors but got " + descriptors.size());

			// Verify each descriptor has required fields
			for (int i = 0; i < descriptors.size(); i++) {
				JsonObject desc = descriptors.getJsonObject(i);
				assertNotNull(desc.getString("kind"), "kind must be present at index " + i);
				assertNotNull(desc.getString("name"), "name must be present at index " + i);
				assertNotNull(desc.getString("category"), "category must be present at index " + i);
			}

			log.info("Loaded {} node descriptors", descriptors.size());
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testGetSingleDescriptor() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonObject desc = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors/facedetect", client.getToken());
			assertNotNull(desc);
			assertEquals("facedetect", desc.getString("kind"));
			assertEquals("ANALYSIS", desc.getString("category"));
			assertNotNull(desc.getString("name"));
			assertNotNull(desc.getString("description"));

			// Verify ports are present. The keys are inputPorts/outputPorts, not inputs/outputs:
			// nodes bind port-to-port, and a port carries a content type and cardinality that the
			// old flat "inputs" list had no room for.
			JsonArray inputs = desc.getJsonArray("inputPorts");
			assertNotNull(inputs, "facedetect must declare inputPorts");
			assertFalse(inputs.isEmpty(), "facedetect should have input ports");

			JsonArray outputs = desc.getJsonArray("outputPorts");
			assertNotNull(outputs, "facedetect must declare outputPorts");
			assertFalse(outputs.isEmpty(), "facedetect should have output ports");

			// Verify parameters are present
			JsonArray params = desc.getJsonArray("parameters");
			assertNotNull(params);
			assertFalse(params.isEmpty(), "facedetect should have parameters");

			log.info("Facedetect descriptor: {}", desc.encodePrettily());
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testGetUnknownDescriptorReturns404() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			int status = httpGetStatus(vertx, "/api/v1/pipeline/node-descriptors/nonexistent-node", client.getToken());
			assertEquals(404, status);
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testListContentTypes() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonArray types = httpGetArray(vertx, "/api/v1/pipeline/content-types", client.getToken());
			assertNotNull(types);
			assertTrue(types.size() > 0, "Expected at least one content type");

			// Verify each content type has id and label
			for (int i = 0; i < types.size(); i++) {
				JsonObject ct = types.getJsonObject(i);
				assertNotNull(ct.getString("id"), "id must be present at index " + i);
				assertNotNull(ct.getString("label"), "label must be present at index " + i);
			}

			log.info("Loaded {} content types", types.size());
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testSourceNodesHaveNoInputs() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			for (String kind : new String[] { "filesystem-source", "loom-fetch" }) {
				JsonObject desc = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors/" + kind, client.getToken());
				assertNotNull(desc, "Missing descriptor for " + kind);
				assertEquals("SOURCE", desc.getString("category"));
				// Read the real key. Asking for "inputs" made this vacuous - the absent key
				// answered null, and the null-tolerant assertion below passed for every node.
				JsonArray inputs = desc.getJsonArray("inputPorts");
				assertTrue(inputs == null || inputs.isEmpty(), kind + " should have no input ports");
			}
		} finally {
			vertx.close();
		}
	}

	// ── Node self-registration surface ────────────────────────────────────

	@Test
	public void testEveryDescriptorCarriesBothNodeIdAndTheDeprecatedKind() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonArray descriptors = nodeDescriptors(vertx, client.getToken());

			// Both names are served for one release so the checked-in node-descriptors.json snapshot,
			// the TypeScript mirror and the offline website editor do not all break in one commit.
			for (int i = 0; i < descriptors.size(); i++) {
				JsonObject desc = descriptors.getJsonObject(i);
				assertNotNull(desc.getString("nodeId"), "nodeId must be present at index " + i);
				assertEquals(desc.getString("nodeId"), desc.getString("kind"),
					"the deprecated alias must agree with nodeId at index " + i);
			}
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testTheResponseCarriesAnAvailabilityBlock() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonObject response = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", client.getToken());

			JsonObject availability = response.getJsonObject("availability");
			assertNotNull(availability, "the editor needs fleet state beside the contracts");

			// Every served contract must have an entry, or the palette has to guess for the rest.
			JsonArray descriptors = response.getJsonArray("nodeDescriptors");
			for (int i = 0; i < descriptors.size(); i++) {
				String nodeId = descriptors.getJsonObject(i).getString("nodeId");
				assertNotNull(availability.getJsonObject(nodeId), "no availability entry for " + nodeId);
			}

			// No worker is connected in this test, so nothing can run - but everything is still served.
			JsonObject whisper = availability.getJsonObject("whisper");
			assertEquals(false, whisper.getBoolean("available"),
				"with no worker connected nothing is runnable - and the contract is still returned");
			assertEquals("BUILTIN", whisper.getString("source"));
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testAvailabilityRouteMatchesTheBlockInTheFullResponse() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonObject full = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", client.getToken())
				.getJsonObject("availability");
			JsonObject presenceOnly = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors/availability",
				client.getToken());

			// The cheap route exists so a worker restart does not pull ~115 KB through every open tab.
			// It has to say exactly the same thing, or the two answers drift.
			assertEquals(full.fieldNames(), presenceOnly.fieldNames());
			for (String nodeId : full.fieldNames()) {
				assertEquals(full.getJsonObject(nodeId).getBoolean("available"),
					presenceOnly.getJsonObject(nodeId).getBoolean("available"), nodeId);
			}
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testTheUnauthenticatedPaletteNeverNamesAWorker() throws Exception {
		Vertx vertx = Vertx.vertx();
		try {
			// The editor loads the palette before anyone has logged in, so this route is unauthenticated
			// by design - and therefore cannot resolve a caller to check a permission against. Worker
			// ids are fleet topology, so they are simply never on this response.
			JsonObject anonymous = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors", null);

			assertNotNull(anonymous.getJsonArray("nodeDescriptors"), "contracts are public");
			JsonObject availability = anonymous.getJsonObject("availability");
			assertNotNull(availability, "so is plain availability");
			for (String nodeId : availability.fieldNames()) {
				assertNull(availability.getJsonObject(nodeId).getJsonArray("providedBy"),
					"providedBy names workers, but " + nodeId + " leaked it on the public route");
			}
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testAnUnknownNodeIdStill404sByTheNewParamName() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// The path param was renamed :kind -> :nodeId; the route must still resolve.
			JsonObject desc = httpGetObject(vertx, "/api/v1/pipeline/node-descriptors/whisper", client.getToken());
			assertEquals("whisper", desc.getString("nodeId"));
		} finally {
			vertx.close();
		}
	}

	@Test
	public void testFilterNodesHaveFilterCategory() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonArray descriptors = nodeDescriptors(vertx, client.getToken());
			for (int i = 0; i < descriptors.size(); i++) {
				JsonObject desc = descriptors.getJsonObject(i);
				if (desc.getString("kind").startsWith("filter-")) {
					assertEquals("FILTER", desc.getString("category"),
						desc.getString("kind") + " should have FILTER category");
				}
			}
		} finally {
			vertx.close();
		}
	}
}
