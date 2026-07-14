package io.metaloom.loom.core.endpoint.test;

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
import io.metaloom.loom.core.endpoint.AbstractGraphQLEndpointTest;
import io.metaloom.loom.rest.model.graphql.GraphQLRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;

public class GraphQLEndpointTest extends AbstractGraphQLEndpointTest {

	@Override
	public void testExecuteGraphQL(LoomHttpClient client) throws LoomClientException {
		// Test basic assets query
		String query = "{ assets { uuid filename mimeType size } }";
		GraphQLRequest request = new GraphQLRequest(query);
		GraphQLResponse response = client.executeGraphQL(request).sync().body();

		assertNotNull(response);
		assertNotNull(response.getData());
		assertTrue(response.getErrors() == null || response.getErrors().isEmpty(), "Expected no errors but got: " + response.getErrors());

		Map<String, Object> data = response.getData().getMap();
		List<Map<String, Object>> assets = (List<Map<String, Object>>) data.get("assets");
		assertNotNull(assets);
		// Test fixture provides at least 2 assets
		assertTrue(assets.size() >= 2, "Expected at least 2 assets but got: " + assets.size());

		for (Map<String, Object> asset : assets) {
			assertNotNull(asset.get("uuid"));
			assertNotNull(asset.get("filename"));
			assertNotNull(asset.get("mimeType"));
			assertNotNull(asset.get("size"));
		}
	}

	@Test
	public void testExecuteGraphQLWithVariables() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// First get an asset UUID to use in the query
			String listQuery = "{ assets { uuid } }";
			GraphQLRequest listRequest = new GraphQLRequest(listQuery);
			GraphQLResponse listResponse = client.executeGraphQL(listRequest).sync().body();

			assertNotNull(listResponse.getData());
			Map<String, Object> listData = listResponse.getData().getMap();
			List<Map<String, Object>> assets = (List<Map<String, Object>>) listData.get("assets");
			assertTrue(assets.size() > 0, "Expected at least one asset");

			String assetUuid = (String) assets.get(0).get("uuid");

			// Now query with variable
			String query = "query GetAsset($uuid: ID!) { asset(uuid: $uuid) { uuid filename mimeType size sha512 sha256 md5 } }";
			io.vertx.core.json.JsonObject variables = new io.vertx.core.json.JsonObject().put("uuid", assetUuid);
			GraphQLRequest request = new GraphQLRequest(query, variables);
			GraphQLResponse response = client.executeGraphQL(request).sync().body();

			assertNotNull(response);
			assertNotNull(response.getData());
			assertTrue(response.getErrors() == null || response.getErrors().isEmpty(), "Expected no errors but got: " + response.getErrors());

			Map<String, Object> data = response.getData().getMap();
			Map<String, Object> asset = (Map<String, Object>) data.get("asset");
			assertNotNull(asset);
			assertEquals(assetUuid, asset.get("uuid"));
			assertNotNull(asset.get("filename"));
			assertNotNull(asset.get("mimeType"));
			assertNotNull(asset.get("size"));
			assertNotNull(asset.get("sha512"));
			assertNotNull(asset.get("sha256"));
			assertNotNull(asset.get("md5"));
		}
	}

	@Test
	public void testExecuteGraphQLWithNestedComponents() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// Get an asset UUID
			String listQuery = "{ assets { uuid } }";
			GraphQLRequest listRequest = new GraphQLRequest(listQuery);
			GraphQLResponse listResponse = client.executeGraphQL(listRequest).sync().body();

			assertNotNull(listResponse.getData());
			Map<String, Object> listData = listResponse.getData().getMap();
			List<Map<String, Object>> assets = (List<Map<String, Object>>) listData.get("assets");
			assertTrue(assets.size() > 0, "Expected at least one asset");

			String assetUuid = (String) assets.get(0).get("uuid");

			// Query with nested components
			String query = "query GetAsset($uuid: ID!) { asset(uuid: $uuid) { uuid filename imageComponents { uuid source dominantColor width height } videoComponents { uuid source } audioComponents { uuid source } locations { uuid path libraryUuid mimeType } } }";
			io.vertx.core.json.JsonObject variables = new io.vertx.core.json.JsonObject().put("uuid", assetUuid);
			GraphQLRequest request = new GraphQLRequest(query, variables);
			GraphQLResponse response = client.executeGraphQL(request).sync().body();

			assertNotNull(response);
			assertNotNull(response.getData());
			assertTrue(response.getErrors() == null || response.getErrors().isEmpty(), "Expected no errors but got: " + response.getErrors());

			Map<String, Object> data = response.getData().getMap();
			Map<String, Object> asset = (Map<String, Object>) data.get("asset");
			assertNotNull(asset);
			assertEquals(assetUuid, asset.get("uuid"));

			// Verify nested structures exist (may be empty arrays)
			assertNotNull(asset.get("imageComponents"));
			assertNotNull(asset.get("videoComponents"));
			assertNotNull(asset.get("audioComponents"));
			assertNotNull(asset.get("locations"));
		}
	}

	@Test
	public void testExecuteGraphQLAssetNotFound() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// Query for non-existent asset
			String query = "query GetAsset($uuid: ID!) { asset(uuid: $uuid) { uuid filename } }";
			io.vertx.core.json.JsonObject variables = new io.vertx.core.json.JsonObject().put("uuid", UUID.randomUUID().toString());
			GraphQLRequest request = new GraphQLRequest(query, variables);
			GraphQLResponse response = client.executeGraphQL(request).sync().body();

			assertNotNull(response);
			assertNotNull(response.getData());
			assertTrue(response.getErrors() == null || response.getErrors().isEmpty(), "Expected no errors but got: " + response.getErrors());

			Map<String, Object> data = response.getData().getMap();
			assertNull(data.get("asset"), "Expected null asset for non-existent UUID");
		}
	}

	@Test
	public void testExecuteGraphQLInvalidQuery() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// Invalid query - missing required field
			String query = "{ asset { uuid } }"; // missing required uuid argument
			GraphQLRequest request = new GraphQLRequest(query);
			GraphQLResponse response = client.executeGraphQL(request).sync().body();

			assertNotNull(response);
			assertNotNull(response.getErrors());
			assertTrue(response.getErrors().size() > 0, "Expected GraphQL errors for invalid query");
		}
	}

}