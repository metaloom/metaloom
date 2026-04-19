package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentListResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentType;
import io.metaloom.loom.rest.model.asset.AssetComponentUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.vertx.core.json.JsonObject;

public class AssetComponentEndpointTest extends AbstractEndpointTest {

	@Test
	public void testCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse response = createJsonComponent(client, ASSET_UUID, "test-schema", new JsonObject().put("key", "value"));
			assertNotNull(response.getUuid());
			assertEquals(AssetComponentType.JSON, response.getType());
			assertEquals(ASSET_UUID, response.getAssetUuid());
			assertNotNull(response.getJson());
			assertEquals("test-schema", response.getJson().getSchemaType());
			assertEquals("value", response.getJson().getData().getString("key"));
		}
	}

	@Test
	public void testRead() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse created = createJsonComponent(client, ASSET_UUID, "read-schema", new JsonObject().put("foo", "bar"));
			UUID compUuid = created.getUuid();

			AssetComponentResponse loaded = client.loadAssetComponent(ASSET_UUID, compUuid).sync().body();
			assertNotNull(loaded);
			assertEquals(compUuid, loaded.getUuid());
			assertEquals(AssetComponentType.JSON, loaded.getType());
			assertEquals("read-schema", loaded.getJson().getSchemaType());
			assertEquals("bar", loaded.getJson().getData().getString("foo"));
		}
	}

	@Test
	public void testUpdate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse created = createJsonComponent(client, ASSET_UUID, "old-schema", new JsonObject().put("a", 1));
			UUID compUuid = created.getUuid();

			AssetComponentUpdateRequest updateRequest = new AssetComponentUpdateRequest();
			updateRequest.setType(AssetComponentType.JSON);
			updateRequest.setJson(new JsonComponentInfo()
				.setSchemaType("new-schema")
				.setData(new JsonObject().put("a", 2)));
			AssetComponentResponse updated = client.updateAssetComponent(ASSET_UUID, compUuid, updateRequest).sync().body();
			assertNotNull(updated);
			assertEquals("new-schema", updated.getJson().getSchemaType());
			assertEquals(2, updated.getJson().getData().getInteger("a"));
		}
	}

	@Test
	public void testDelete() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse created = createJsonComponent(client, ASSET_UUID, "delete-schema", new JsonObject().put("x", true));
			UUID compUuid = created.getUuid();

			client.deleteAssetComponent(ASSET_UUID, compUuid).sync().body();
			expect(404, "Not Found", client.loadAssetComponent(ASSET_UUID, compUuid));
		}
	}

	@Test
	public void testList() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// Create a few components
			createJsonComponent(client, ASSET_UUID, "list-schema-1", new JsonObject().put("i", 1));
			createJsonComponent(client, ASSET_UUID, "list-schema-2", new JsonObject().put("i", 2));

			AssetComponentListResponse list = client.listAssetComponents(ASSET_UUID).sync().body();
			assertNotNull(list);
			assertNotNull(list.getData());
			// At least the 2 we just created
			assertEquals(2, list.getData().size());
		}
	}

	private AssetComponentResponse createJsonComponent(LoomHttpClient client, UUID assetUuid, String schemaType, JsonObject data)
		throws LoomClientException {
		AssetComponentCreateRequest request = new AssetComponentCreateRequest();
		request.setType(AssetComponentType.JSON);
		request.setSource("unit-test");
		request.setJson(new JsonComponentInfo()
			.setSchemaType(schemaType)
			.setData(data));
		return client.createAssetComponent(assetUuid, request).sync().body();
	}
}
