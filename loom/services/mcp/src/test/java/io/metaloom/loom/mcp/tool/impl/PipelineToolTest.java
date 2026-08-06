package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The pipeline MCP tools, against mocked DAOs.
 *
 * <p>The property that matters for the chat is the double rendering: the graph must be complete in the <em>text</em> content — that is all the model ever
 * sees — and additionally present as a {@code pipeline-graph} visual the UI can draw (MCP.md §5.0, CHAT.md §6.1).</p>
 */
public class PipelineToolTest {

	private static final UUID PIPELINE_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID VERSION_UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	private DaoCollection daos;
	private PipelineDao pipelineDao;
	private PipelineVersionDao versionDao;
	private NodeDescriptorRegistry registry;

	@BeforeEach
	public void setup() {
		daos = mock(DaoCollection.class);
		pipelineDao = mock(PipelineDao.class);
		versionDao = mock(PipelineVersionDao.class);
		when(daos.pipelineDao()).thenReturn(pipelineDao);
		when(daos.pipelineVersionDao()).thenReturn(versionDao);

		registry = new NodeDescriptorRegistry();
		registry.register(new NodeDescriptor().setNodeId("filesystem-source").setName("File Source").setCategory(NodeCategory.SOURCE));
		registry.register(new NodeDescriptor().setNodeId("whisper").setName("Whisper").setCategory(NodeCategory.ANALYSIS));

		Pipeline pipeline = mock(Pipeline.class);
		when(pipeline.getUuid()).thenReturn(PIPELINE_UUID);
		when(pipeline.getLatestVersionUuid()).thenReturn(VERSION_UUID);
		when(pipelineDao.loadWithLatestVersion(PIPELINE_UUID)).thenReturn(pipeline);
		when(pipelineDao.loadPage(any(), anyInt(), any(), any(), any())).thenReturn(page(List.of(pipeline)));

		PipelineVersion version = mock(PipelineVersion.class);
		when(version.getUuid()).thenReturn(VERSION_UUID);
		when(version.getName()).thenReturn("Media Transcription");
		when(version.getDescription()).thenReturn("Transcribes speech with Whisper.");
		when(version.getVersionNumber()).thenReturn(3);
		when(version.isEnabled()).thenReturn(true);
		when(version.getPriority()).thenReturn(4);
		when(version.getDefinition()).thenReturn(definition());
		when(versionDao.loadLatestByPipeline(PIPELINE_UUID)).thenReturn(version);
		when(versionDao.loadByUuids(any())).thenReturn(List.of(version));
	}

	private static JsonObject definition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("label", "Media Source").put("x", 60).put("y", 160))
				.add(new JsonObject().put("id", "pn2").put("type", "whisper").put("label", "Transcribe").put("x", 260).put("y", 160)))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "video")));
	}

	private static Page<Pipeline> page(List<Pipeline> pipelines) {
		return new Page<>(pipelines.size(), pipelines.size(), pipelines);
	}

	private JsonObject callGetPipeline(String pipelineId) {
		return new GetPipelineTool(daos, new PipelineGraphRenderer(daos, registry))
			.execute(new JsonObject().put("pipelineId", pipelineId))
			.result();
	}

	private static String text(JsonObject result) {
		return result.getJsonArray("content").getJsonObject(0).getString("text");
	}

	@Test
	public void testGetPipelineByUuidCarriesGraphAndVisual() {
		JsonObject result = callGetPipeline(PIPELINE_UUID.toString());

		// Text: the model reads this and nothing else
		String text = text(result);
		assertTrue(text.contains("Media Transcription"), text);
		assertTrue(text.contains("pn1 Media Source [filesystem-source, SOURCE]"), text);
		assertTrue(text.contains("pn1.media -> pn2.video"), text);
		assertTrue(text.contains("version: 3"), text);

		// Reference: renders as the pipeline chip
		JsonObject reference = result.getJsonArray("references").getJsonObject(0);
		assertEquals("pipeline", reference.getString("type"));
		assertEquals(PIPELINE_UUID.toString(), reference.getString("uuid"));

		// Visual: the compact diagram
		JsonObject visual = result.getJsonArray("visuals").getJsonObject(0);
		assertEquals(GetPipelineTool.VISUAL_TYPE, visual.getString("type"));
		assertEquals(PIPELINE_UUID.toString(), visual.getString("uuid"));
		JsonObject payload = visual.getJsonObject("payload");
		assertEquals("Media Transcription", payload.getString("name"));
		assertEquals(3, payload.getInteger("versionNumber"));

		JsonObject node = payload.getJsonArray("nodes").getJsonObject(0);
		assertEquals("pn1", node.getString("id"));
		assertEquals("filesystem-source", node.getString("kind"));
		assertEquals("Media Source", node.getString("label"));
		assertEquals("SOURCE", node.getString("category"), "The category comes from the node descriptor registry");
		assertNull(node.getInteger("x"), "Editor-only coordinates must not be shipped — the chat lays the graph out itself");

		JsonObject edge = payload.getJsonArray("edges").getJsonObject(0);
		assertEquals("pn1", edge.getString("source"));
		assertEquals("media", edge.getString("sourcePort"));
		assertEquals("video", edge.getString("targetPort"));
	}

	/**
	 * Users ask for "the transcription pipeline", so the model passes a name — resolving only uuids would make the tool unusable in a chat.
	 */
	@Test
	public void testGetPipelineResolvesByNameAndPartialName() {
		assertEquals("Media Transcription",
			callGetPipeline("media transcription").getJsonArray("visuals").getJsonObject(0).getString("label"));
		assertEquals("Media Transcription",
			callGetPipeline("Transcription").getJsonArray("visuals").getJsonObject(0).getString("label"));
	}

	@Test
	public void testGetPipelineWithUnknownIdHasNoVisual() {
		JsonObject result = callGetPipeline("Colour Grading");
		assertTrue(text(result).startsWith("No pipeline found"));
		assertNull(result.getJsonArray("visuals"), "Nothing to draw means no visual envelope");
	}

	/**
	 * An unknown node kind (a definition written before a kind was removed, or one shipped by a plugin) must still render.
	 */
	@Test
	public void testUnknownNodeKindFallsBackToAnalysis() {
		when(versionDao.loadLatestByPipeline(PIPELINE_UUID).getDefinition()).thenReturn(new JsonObject()
			.put("nodes", new JsonArray().add(new JsonObject().put("id", "pn1").put("type", "no-such-kind")))
			.put("edges", new JsonArray()));

		JsonObject node = callGetPipeline(PIPELINE_UUID.toString())
			.getJsonArray("visuals").getJsonObject(0).getJsonObject("payload")
			.getJsonArray("nodes").getJsonObject(0);
		assertEquals("ANALYSIS", node.getString("category"));
		assertEquals("no-such-kind", node.getString("label"), "Without a descriptor the kind is the best label available");
	}

	@Test
	public void testListPipelines() {
		JsonObject result = new ListPipelinesTool(daos).execute(new JsonObject()).result();

		String text = text(result);
		assertTrue(text.contains("Media Transcription"), text);
		assertTrue(text.contains("\"nodeCount\" : 2"), text);
		assertNull(result.getJsonArray("visuals"), "A listing carries no graphs — one per row would swamp the context window");
		assertEquals(1, result.getJsonArray("references").size());

		// The query argument filters over name and description
		assertEquals(1, new ListPipelinesTool(daos).execute(new JsonObject().put("query", "whisper")).result()
			.getJsonArray("references").size());
		assertNull(new ListPipelinesTool(daos).execute(new JsonObject().put("query", "colour grading")).result()
			.getJsonArray("references"));
	}

	@Test
	public void testGetPipelineRequiresPipelineId() {
		JsonObject result = new GetPipelineTool(daos, new PipelineGraphRenderer(daos, registry)).execute(new JsonObject()).result();
		assertNotNull(result.getJsonArray("content"));
		assertTrue(text(result).startsWith("ERROR:"));
	}

	@Test
	public void testDescriptors() {
		assertEquals(List.of("READ_PIPELINE"), new GetPipelineTool(daos, new PipelineGraphRenderer(daos, registry)).descriptor().requiredPermissions());
		assertEquals(List.of("READ_PIPELINE"), new ListPipelinesTool(daos).descriptor().requiredPermissions());

		JsonObject schema = new GetPipelineTool(daos, new PipelineGraphRenderer(daos, registry)).descriptor().inputSchema();
		assertTrue(schema.getJsonArray("required").contains("pipelineId"));
	}

	/**
	 * Guard against the payload growing without bound: the caps clip both arrays and flag the result.
	 */
	@Test
	public void testOversizedGraphIsTruncated() {
		JsonArray nodes = new JsonArray();
		for (int i = 0; i < GetPipelineTool.MAX_NODES + 5; i++) {
			nodes.add(new JsonObject().put("id", "pn" + i).put("type", "whisper").put("label", "Node " + i));
		}
		when(versionDao.loadLatestByPipeline(PIPELINE_UUID).getDefinition())
			.thenReturn(new JsonObject().put("nodes", nodes).put("edges", new JsonArray()));

		JsonObject payload = callGetPipeline(PIPELINE_UUID.toString())
			.getJsonArray("visuals").getJsonObject(0).getJsonObject("payload");
		assertEquals(GetPipelineTool.MAX_NODES, payload.getJsonArray("nodes").size());
		assertTrue(payload.getBoolean("truncated"));
	}

}
