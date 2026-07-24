package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompListResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.vertx.core.json.JsonObject;

public class JsonCompEndpointTest extends AbstractEndpointTest {

	@Test
	public void testCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonCompResponse response = createJsonComp(client, "hello-world", "hello-world", "", 2048, 137);
			assertNotNull(response.getUuid());
			assertEquals(ASSET_UUID.toString(), response.getAssetUuid());
			assertEquals("hello-world", response.getNodeKind());
			assertEquals("hello-world", response.getSchemaType());
			assertEquals(2048, response.getData().getInteger("file_size"));
			assertEquals(137, response.getData().getInteger("word_count"));
		}
	}

	@Test
	public void testList() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			createJsonComp(client, "hello-world", "hello-world", "", 1, 1);
			createJsonComp(client, "caption", "caption", "", 2, 2);

			JsonCompListResponse list = client.listAssetJsonComps(ASSET_UUID).sync().body();
			assertNotNull(list);
			assertEquals(2, list.getData().size());
		}
	}

	/**
	 * Re-posting the same (asset, node_kind, schema_type, variant) rewrites the single component row.
	 */
	@Test
	public void testUpsertReplacesInPlace() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			createJsonComp(client, "hello-world", "hello-world", "", 10, 10);
			createJsonComp(client, "hello-world", "hello-world", "", 99, 99);

			JsonCompListResponse list = client.listAssetJsonComps(ASSET_UUID).sync().body();
			assertEquals(1, list.getData().size());
			assertEquals(99, list.getData().get(0).getData().getInteger("file_size"));
		}
	}

	@Test
	public void testLoadAndDelete() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonCompResponse created = createJsonComp(client, "hello-world", "hello-world", "", 5, 5);
			var uuid = created.getUuid();

			JsonCompResponse loaded = client.loadAssetJsonComp(ASSET_UUID, uuid).sync().body();
			assertEquals(uuid, loaded.getUuid());

			client.deleteAssetJsonComp(ASSET_UUID, uuid).sync().body();
			expect(404, "Not Found", client.loadAssetJsonComp(ASSET_UUID, uuid));
		}
	}

	@Test
	public void testValidationRejectsMissingSchemaType() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind("hello-world");
			// schemaType deliberately omitted
			request.setData(new JsonObject().put("file_size", 1));
			expect(400, "Bad Request", client.createAssetJsonComp(ASSET_UUID, request));
		}
	}

	private JsonCompResponse createJsonComp(LoomHttpClient client, String nodeKind, String schemaType, String variant, int fileSize, int wordCount)
		throws LoomClientException {
		JsonCompCreateRequest request = new JsonCompCreateRequest();
		request.setNodeKind(nodeKind);
		request.setSchemaType(schemaType);
		request.setVariant(variant);
		request.setProducerVersion("1.0");
		request.setData(new JsonObject()
			.put("file_size", fileSize)
			.put("word_count", wordCount));
		return client.createAssetJsonComp(ASSET_UUID, request).sync().body();
	}
}
