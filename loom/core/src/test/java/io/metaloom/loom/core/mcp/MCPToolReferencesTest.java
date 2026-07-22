package io.metaloom.loom.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.core.mcp.MCPTestClient.HttpResult;
import io.metaloom.loom.test.data.TestValues;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies that the MCP tools attach structured {@code references} envelopes (domain entity type + uuid + label) to their results while keeping the standard
 * MCP {@code content} untouched. The references are consumed by the chat agent to render entity chips.
 */
public class MCPToolReferencesTest implements TestValues {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private JsonObject callTool(String name, JsonObject arguments) throws Exception {
		MCPTestClient client = new MCPTestClient(loom.internal());
		HttpResult result = client.postMessage(MCPTestClient.jsonRpc("tools/call", 1, MCPTestClient.toolCall(name, arguments)));
		assertEquals(200, result.statusCode());
		JsonObject response = result.json();
		assertNull(response.getJsonObject("error"), "The tool call must not fail");
		JsonObject toolResult = response.getJsonObject("result");
		assertNotNull(toolResult, "The tool call should return a result");
		return toolResult;
	}

	private void assertReferences(JsonObject toolResult, String expectedType) {
		// The standard MCP content must be untouched
		JsonArray content = toolResult.getJsonArray("content");
		assertNotNull(content, "The MCP content must be present");
		assertFalse(content.isEmpty(), "The MCP content must not be empty");
		assertEquals("text", content.getJsonObject(0).getString("type"));

		// The references envelope lists the domain entities of the result
		JsonArray references = toolResult.getJsonArray("references");
		assertNotNull(references, "The references envelope must be present");
		assertFalse(references.isEmpty(), "The references envelope must not be empty");
		for (int i = 0; i < references.size(); i++) {
			JsonObject ref = references.getJsonObject(i);
			assertEquals(expectedType, ref.getString("type"));
			assertNotNull(ref.getString("uuid"), "Each reference must carry a uuid");
			assertNotNull(ref.getString("label"), "Each reference must carry a label");
		}
	}

	@Test
	public void testSearchAssetsReferences() throws Exception {
		JsonObject result = callTool("search_assets", new JsonObject().put("limit", 5));
		assertReferences(result, "asset");
	}

	@Test
	public void testGetAssetReferences() throws Exception {
		JsonObject result = callTool("get_asset", new JsonObject().put("assetId", ASSET_UUID.toString()));
		assertReferences(result, "asset");
	}

	@Test
	public void testListCollectionsReferences() throws Exception {
		JsonObject result = callTool("list_collections", new JsonObject().put("limit", 5));
		assertReferences(result, "collection");
	}

	@Test
	public void testAssetStatisticsHasNoReferences() throws Exception {
		JsonObject result = callTool("asset_statistics", new JsonObject());
		assertNotNull(result.getJsonArray("content"));
		assertNull(result.getJsonArray("references"), "Statistics results carry no entity references");
	}

}
