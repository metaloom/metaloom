package io.metaloom.loom.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.core.mcp.MCPTestClient.HttpResult;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
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

	/**
	 * Also the only end-to-end check that {@code search_assets} reaches the real search backend: the term matches the fixture asset through its
	 * trigger-maintained {@code search_document}, so a reference here means the SPI answered, not that a page of the catalogue was listed.
	 */
	@Test
	public void testSearchAssetsReferences() throws Exception {
		JsonObject result = callTool("search_assets", new JsonObject().put("query", "bigbuckbunny").put("limit", 5));
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

	@Test
	public void testListPipelinesReferences() throws Exception {
		createPipeline("mcp-list-" + UUID.randomUUID());
		assertReferences(callTool("list_pipelines", new JsonObject().put("limit", 25)), "pipeline");
	}

	/**
	 * The graph tool against real DAOs: the version lookup, the descriptor resolution and the visual envelope, end to end over JSON-RPC.
	 */
	@Test
	public void testGetPipelineCarriesAGraphVisual() throws Exception {
		String name = "mcp-graph-" + UUID.randomUUID();
		createPipeline(name);

		JsonObject result = callTool("get_pipeline", new JsonObject().put("pipelineId", name));
		assertReferences(result, "pipeline");

		// The text is what the model reads — it must describe the graph on its own
		String text = result.getJsonArray("content").getJsonObject(0).getString("text");
		assertTrue(text.contains(name), text);
		assertTrue(text.contains("pn1.media -> pn2.video"), text);

		JsonArray visuals = result.getJsonArray("visuals");
		assertNotNull(visuals, "The visuals envelope must be present");
		JsonObject visual = visuals.getJsonObject(0);
		assertEquals("pipeline-graph", visual.getString("type"));
		assertEquals(name, visual.getString("label"));

		JsonObject payload = visual.getJsonObject("payload");
		assertEquals(2, payload.getJsonArray("nodes").size());
		assertEquals(1, payload.getJsonArray("edges").size());
		assertEquals("SOURCE", payload.getJsonArray("nodes").getJsonObject(0).getString("category"),
			"The category is resolved through the node descriptor registry");
		assertNull(payload.getJsonArray("nodes").getJsonObject(0).getInteger("x"), "Editor coordinates must not be shipped");
	}

	@Test
	public void testGetPipelineWithUnknownIdHasNoVisual() throws Exception {
		JsonObject result = callTool("get_pipeline", new JsonObject().put("pipelineId", "no-such-pipeline-" + UUID.randomUUID()));
		assertNull(result.getJsonArray("visuals"));
		assertNull(result.getJsonArray("references"));
	}

	/**
	 * Create a pipeline with a v1 version whose definition is a two node graph.
	 */
	private void createPipeline(String name) {
		DaoCollection daos = loom.internal().daos();
		UUID adminUuid = daos.userDao().loadAdmin().getUuid();
		Pipeline pipeline = daos.pipelineDao().createPipeline(adminUuid, name);
		daos.pipelineDao().store(pipeline);

		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("label", "Media Source").put("x", 60).put("y", 160))
				.add(new JsonObject().put("id", "pn2").put("type", "whisper").put("label", "Transcribe").put("x", 260).put("y", 160)))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "video")));

		PipelineVersion version = daos.pipelineVersionDao().createVersion(adminUuid, pipeline.getUuid(), 1, name,
			"Created by " + getClass().getSimpleName(), definition, true, 1, false, new JsonObject());
		daos.pipelineVersionDao().store(version);

		pipeline.setLatestVersionUuid(version.getUuid());
		daos.pipelineDao().update(pipeline);
	}

}
