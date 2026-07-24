package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultListResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class NodeResultEndpointTest extends AbstractEndpointTest {

	@Test
	public void testCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			NodeResultResponse response = createNodeResult(client, "whisper", "", "SUCCESS", UUID.randomUUID());
			assertNotNull(response.getUuid());
			assertEquals(ASSET_UUID.toString(), response.getAssetUuid());
			assertEquals("whisper", response.getNodeKind());
			assertEquals("SUCCESS", response.getState());
			assertEquals("COMPUTED", response.getOrigin());
			assertEquals("asset_transcript_comp", response.getResultRef().getString("table"));
		}
	}

	@Test
	public void testList() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			createNodeResult(client, "whisper", "", "SUCCESS", UUID.randomUUID());
			createNodeResult(client, "ocr", "", "SUCCESS", UUID.randomUUID());

			NodeResultListResponse list = client.listAssetNodeResults(ASSET_UUID).sync().body();
			assertNotNull(list);
			assertEquals(2, list.getData().size());
		}
	}

	/**
	 * Re-posting the same (asset, node_kind, node_id) rewrites the single ledger row.
	 */
	@Test
	public void testUpsertReplacesInPlace() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			createNodeResult(client, "whisper", "", "SUCCESS", UUID.randomUUID());
			createNodeResult(client, "whisper", "", "FAILED", UUID.randomUUID());

			NodeResultListResponse list = client.listAssetNodeResults(ASSET_UUID).sync().body();
			assertEquals(1, list.getData().size());
			assertEquals("FAILED", list.getData().get(0).getState());
		}
	}

	@Test
	public void testLoadAndDelete() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			NodeResultResponse created = createNodeResult(client, "whisper", "", "SUCCESS", UUID.randomUUID());
			UUID uuid = created.getUuid();

			NodeResultResponse loaded = client.loadAssetNodeResult(ASSET_UUID, uuid).sync().body();
			assertEquals(uuid, loaded.getUuid());

			client.deleteAssetNodeResult(ASSET_UUID, uuid).sync().body();
			expect(404, "Not Found", client.loadAssetNodeResult(ASSET_UUID, uuid));
		}
	}

	@Test
	public void testValidationRejectsMissingState() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			NodeResultCreateRequest request = new NodeResultCreateRequest();
			request.setNodeKind("whisper");
			// state deliberately omitted
			expect(400, "Bad Request", client.createAssetNodeResult(ASSET_UUID, request));
		}
	}

	private NodeResultResponse createNodeResult(LoomHttpClient client, String nodeKind, String nodeId, String state, UUID transcriptUuid)
		throws LoomClientException {
		NodeResultCreateRequest request = new NodeResultCreateRequest();
		request.setNodeKind(nodeKind);
		request.setNodeId(nodeId);
		request.setProducerVersion("ggml-base");
		request.setState(state);
		request.setOrigin("COMPUTED");
		request.setDurationMs(4200L);
		request.setResultRef(new JsonObject()
			.put("table", "asset_transcript_comp")
			.put("uuids", new JsonArray().add(transcriptUuid.toString())));
		return client.createAssetNodeResult(ASSET_UUID, request).sync().body();
	}
}
