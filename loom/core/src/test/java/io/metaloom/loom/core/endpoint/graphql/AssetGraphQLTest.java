package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.db.model.perm.Permission;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the {@code Asset} and {@code AssetLocation} domain elements. The test fixture already provisions a handful of assets (see the
 * fixture provider), so these mostly assert against seeded data rather than creating their own.
 */
public class AssetGraphQLTest extends AbstractGraphQLTest {

	@Test
	public void testAssetByUuid() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", ASSET_UUID.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { asset(uuid: $uuid) { uuid filename mimeType size sha512 } }", variables);

			Map<String, Object> asset = object(data, "asset");
			assertNotNull(asset, "The fixture asset should be resolvable by uuid");
			assertEquals(ASSET_UUID.toString(), asset.get("uuid"));
			assertEquals("bigbuckbunny.mp4", asset.get("filename"));
			assertEquals(IMAGE_MIMETYPE, asset.get("mimeType"));
			assertEquals(SHA512SUM.toString(), asset.get("sha512"));
		}
	}

	@Test
	public void testAssetBySha512() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("sha512", SHA512SUM.toString());
			Map<String, Object> data = data(client,
				"query($sha512: String!) { assetBySha512(sha512: $sha512) { uuid filename } }", variables);

			Map<String, Object> asset = object(data, "assetBySha512");
			assertNotNull(asset, "The asset should be resolvable by its checksum");
			assertEquals(ASSET_UUID.toString(), asset.get("uuid"));
			assertEquals("bigbuckbunny.mp4", asset.get("filename"));
		}
	}

	@Test
	public void testAssetList() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			Map<String, Object> data = data(client, "{ assets { uuid filename mimeType size } }");
			List<Map<String, Object>> assets = list(data, "assets");
			assertNotNull(assets);
			assertTrue(assets.size() >= 2, "The fixture provisions several assets, got: " + assets.size());
			for (Map<String, Object> asset : assets) {
				assertNotNull(asset.get("uuid"));
			}
		}
	}

	@Test
	public void testAssetLocations() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", ASSET_UUID.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { asset(uuid: $uuid) { uuid locations { uuid path mimeType asset { uuid } } } }", variables);

			Map<String, Object> asset = object(data, "asset");
			List<Map<String, Object>> locations = list(asset, "locations");
			assertNotNull(locations);
			assertTrue(locations.size() >= 1, "The fixture asset has at least one location");
			Map<String, Object> location = locations.get(0);
			assertNotNull(location.get("path"));
			// The back reference resolves to the owning asset.
			assertEquals(ASSET_UUID.toString(), object(location, "asset").get("uuid"));
		}
	}

	@Test
	public void testAssetLocationsByAssetArgument() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("assetUuid", ASSET_UUID.toString());
			Map<String, Object> data = data(client,
				"query($assetUuid: ID) { assetLocations(assetUuid: $assetUuid) { uuid assetUuid } }", variables);

			List<Map<String, Object>> locations = list(data, "assetLocations");
			assertNotNull(locations);
			assertTrue(locations.size() >= 1);
			for (Map<String, Object> location : locations) {
				assertEquals(ASSET_UUID.toString(), location.get("assetUuid"));
			}
		}
	}

	@Test
	public void testAssetNotFound() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", UUID.randomUUID().toString());
			Map<String, Object> data = data(client, "query($uuid: ID!) { asset(uuid: $uuid) { uuid } }", variables);
			assertNull(data.get("asset"), "An unknown asset uuid must resolve to null");
		}
	}

	@Test
	public void testMalformedUuidIsBadUserInput() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			assertErrorCode(query(client, "{ asset(uuid: \"not-a-uuid\") { uuid } }"), "BAD_USER_INPUT");
		}
	}

	@Test
	@Override
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_ASSET, "{ asset(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_ASSET, "{ assetBySha512(sha512: \"" + SHA512SUM + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_ASSET_LOCATION, "{ assetLocation(uuid: \"" + uuid + "\") { uuid } }");
		}
	}

	@Test
	@Override
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			assertRetrievalForbidden(client, Permission.READ_ASSET, "{ assets { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_ASSET_LOCATION, "{ assetLocations { uuid } }");
		}
	}
}
