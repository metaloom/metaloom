package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputCreateRequest;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputListResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputLookupResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The node output reuse endpoints: a node looks up before it works and stores after it computed.
 */
public class NodeOutputEndpointTest extends AbstractEndpointTest {

	private static final String KEY = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

	@Test
	public void testCreateAndLookupByUuid() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			NodeOutputResponse created = store(client, "whisper", KEY, "transcribe", "hello");
			assertNotNull(created.getUuid());
			assertEquals(ASSET_UUID.toString(), created.getAssetUuid());
			assertEquals("transcribe", created.getNodeId());

			NodeOutputLookupResponse hit = client.lookupNodeOutput(ASSET_UUID, "whisper", KEY).sync().body();
			assertEquals(ASSET_UUID, hit.getAsset().getUuid(), "The asset travels with the answer");
			assertNotNull(hit.getOutput(), "A stored output is a hit");
			assertEquals("hello", hit.getOutput().getOutputs().getJsonObject("transcript").getJsonArray("values").getString(0));
		}
	}

	/**
	 * A worker knows the file's hash, not the asset uuid, so the hash form is the one every node calls. A miss still answers 200 with the asset - the
	 * node needs it to compute and persist.
	 */
	@Test
	public void testLookupByHashAndMiss() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			store(client, "ocr", KEY, "ocr", "text");
			SHA512 hash = daos().assetDao().load(ASSET_UUID).getSHA512();

			NodeOutputLookupResponse hit = client.lookupNodeOutput(hash, "ocr", KEY).sync().body();
			assertNotNull(hit.getOutput());

			NodeOutputLookupResponse miss = client.lookupNodeOutput(hash, "ocr", "0000").sync().body();
			assertEquals(ASSET_UUID, miss.getAsset().getUuid());
			assertNull(miss.getOutput(), "Another key is a miss");

			NodeOutputLookupResponse otherKind = client.lookupNodeOutput(hash, "whisper", KEY).sync().body();
			assertNull(otherKind.getOutput(), "The key is scoped by node kind");
		}
	}

	@Test
	public void testLookupUnknownAssetIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.lookupNodeOutput(UUID.randomUUID(), "whisper", KEY));
		}
	}

	/**
	 * Storing the same key again - a recompute, or another pipeline's node instance - replaces the output instead of adding a second answer.
	 */
	@Test
	public void testSameKeyReplaces() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			NodeOutputResponse first = store(client, "sentiment", KEY, "pipeline-a", "first");
			NodeOutputResponse second = store(client, "sentiment", KEY, "pipeline-b", "second");
			assertEquals(first.getUuid(), second.getUuid());

			NodeOutputListResponse list = client.listAssetNodeOutputs(ASSET_UUID, "sentiment").sync().body();
			assertEquals(1, list.getData().size());
			assertEquals("pipeline-b", list.getData().get(0).getNodeId());
		}
	}

	@Test
	public void testListAndDeleteByKind() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			store(client, "caption", KEY, "caption", "a cat");
			store(client, "llm", KEY, "llm", "a summary");

			assertTrue(client.listAssetNodeOutputs(ASSET_UUID, null).sync().body().getData().size() >= 2);
			assertEquals(1, client.listAssetNodeOutputs(ASSET_UUID, "caption").sync().body().getData().size());

			client.deleteAssetNodeOutputs(ASSET_UUID, "caption").sync();
			assertNull(client.lookupNodeOutput(ASSET_UUID, "caption", KEY).sync().body().getOutput(), "The deleted kind computes again");
			assertNotNull(client.lookupNodeOutput(ASSET_UUID, "llm", KEY).sync().body().getOutput(), "Another kind is untouched");

			client.deleteAssetNodeOutputs(ASSET_UUID, null).sync();
			assertEquals(0, client.listAssetNodeOutputs(ASSET_UUID, null).sync().body().getData().size());
		}
	}

	/** A failure is never stored: replaying one would turn a transient outage into a permanent answer. */
	@Test
	public void testValidationRejectsFailedState() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			NodeOutputCreateRequest request = request("whisper", KEY, "whisper", "x").setState("FAILED");
			expect(400, "Bad Request", client.createAssetNodeOutput(ASSET_UUID, request));
		}
	}

	@Test
	public void testValidationRejectsMissingKeyAndOutputs() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.createAssetNodeOutput(ASSET_UUID, request("whisper", null, "whisper", "x")));
			expect(400, "Bad Request", client.createAssetNodeOutput(ASSET_UUID, request("whisper", KEY, "whisper", "x").setOutputs(null)));
			expect(400, "Bad Request", client.createAssetNodeOutput(ASSET_UUID, request("whisper", KEY, "whisper", "x").setRunUuid("not-a-uuid")));
		}
	}

	@Test
	public void testCreateForUnknownAssetIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.createAssetNodeOutput(UUID.randomUUID(), request("whisper", KEY, "whisper", "x")));
		}
	}

	// --- Permissions ----------------------------------------------------------------------------------------------------------------------------

	@Test
	public void testLookupRequiresReadAsset() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.lookupNodeOutput(ASSET_UUID, "whisper", KEY));
		}
	}

	@Test
	public void testListRequiresReadAsset() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listAssetNodeOutputs(ASSET_UUID, null));
		}
	}

	@Test
	public void testCreateRequiresUpdateAsset() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.createAssetNodeOutput(ASSET_UUID, request("whisper", KEY, "whisper", "x")));
		}
	}

	@Test
	public void testDeleteRequiresUpdateAsset() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.deleteAssetNodeOutputs(ASSET_UUID, null));
		}
	}

	/**
	 * READ_ASSET is enough to look up and list - but not to store or forget: a reader must not be able to plant an output that every later run would
	 * replay as if it had been computed.
	 */
	@Test
	public void testReadAssetCanLookUpButNotWrite() throws Exception {
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			store(admin, "quality", KEY, "quality", "0.9");
		}
		try (LoomHttpClient reader = loginClientWith("node-output-reader", Permission.READ_ASSET)) {
			assertNotNull(reader.lookupNodeOutput(ASSET_UUID, "quality", KEY).sync().body().getOutput());
			assertEquals(1, reader.listAssetNodeOutputs(ASSET_UUID, "quality").sync().body().getData().size());
			expect(403, "Forbidden", reader.createAssetNodeOutput(ASSET_UUID, request("quality", KEY, "quality", "0.1")));
			expect(403, "Forbidden", reader.deleteAssetNodeOutputs(ASSET_UUID, "quality"));
		}
	}

	/** UPDATE_ASSET together with READ_ASSET is what a Cortex worker's token carries, and that is all it needs. */
	@Test
	public void testWorkerPermissionsSuffice() throws Exception {
		try (LoomHttpClient worker = loginClientWith("node-output-worker", Permission.READ_ASSET, Permission.UPDATE_ASSET)) {
			store(worker, "tika", KEY, "tika", "body text");
			assertNotNull(worker.lookupNodeOutput(ASSET_UUID, "tika", KEY).sync().body().getOutput());
			worker.deleteAssetNodeOutputs(ASSET_UUID, "tika").sync();
			assertNull(worker.lookupNodeOutput(ASSET_UUID, "tika", KEY).sync().body().getOutput());
		}
	}

	private NodeOutputResponse store(LoomHttpClient client, String nodeKind, String key, String nodeId, String value) throws LoomClientException {
		return client.createAssetNodeOutput(ASSET_UUID, request(nodeKind, key, nodeId, value)).sync().body();
	}

	private static NodeOutputCreateRequest request(String nodeKind, String key, String nodeId, String value) {
		return new NodeOutputCreateRequest()
			.setNodeKind(nodeKind)
			.setReuseKey(key)
			.setNodeId(nodeId)
			.setProducerVersion("v1")
			.setState("SUCCESS")
			.setOutputs(new JsonObject().put("transcript", new JsonObject()
				.put("contentType", "text/transcript")
				.put("cardinality", "ONE")
				.put("values", new JsonArray().add(value))));
	}
}
