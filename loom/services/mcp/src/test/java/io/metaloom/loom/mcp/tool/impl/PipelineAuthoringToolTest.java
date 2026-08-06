package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.PipelineValidationService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The three authoring tools, against mocked DAOs but a <b>real</b> validator and descriptor registry.
 *
 * <p>
 * The validator is real on purpose: the property worth testing is that a definition the agent hands in is judged by the same rules the REST create
 * path applies, and a mocked validator would let every one of these tests pass while the real graph checks were bypassed.
 * </p>
 */
public class PipelineAuthoringToolTest {

	private static final UUID USER_UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
	private static final UUID PIPELINE_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID VERSION_UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	private DaoCollection daos;
	private PipelineDao pipelineDao;
	private PipelineVersionDao versionDao;
	private NodeDescriptorRegistry registry;
	private ProcessorRegistry processors;
	private PipelineAuthoringService authoring;
	private PipelineGraphRenderer renderer;

	private static final MCPCallerContext CALLER = new MCPCallerContext(USER_UUID, "tester", Set.of(), null, null);

	@BeforeEach
	public void setup() {
		daos = mock(DaoCollection.class);
		pipelineDao = mock(PipelineDao.class);
		versionDao = mock(PipelineVersionDao.class);
		when(daos.pipelineDao()).thenReturn(pipelineDao);
		when(daos.pipelineVersionDao()).thenReturn(versionDao);

		registry = new NodeDescriptorRegistry();
		registry.register(new NodeDescriptor()
			.setNodeId("filesystem-source")
			.setName("File Source")
			.setCategory(NodeCategory.SOURCE)
			.setOutputPorts(List.of(PortSpec.one("media", "media/*"))));
		registry.register(new NodeDescriptor()
			.setNodeId("sha512")
			.setName("SHA-512")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("media", "media/*")))
			.setOutputPorts(List.of(new PortSpec("hash", "hash/sha512", Cardinality.ONE))));

		processors = mock(ProcessorRegistry.class);
		// No workers connected: every kind is "unsupported", which must be a warning and never a refusal.
		when(processors.selectProcessorForKinds(any(), any())).thenReturn(null);

		authoring = new PipelineAuthoringService(pipelineDao, versionDao, new PipelineValidationService(registry),
			mock(LoomModelValidator.class), registry, processors);
		renderer = new PipelineGraphRenderer(daos, registry);

		Pipeline stored = mock(Pipeline.class);
		when(stored.getUuid()).thenReturn(PIPELINE_UUID);
		when(stored.setMeta(any())).thenReturn(stored);
		when(pipelineDao.createPipeline(any(UUID.class), any())).thenReturn(stored);
		when(pipelineDao.loadWithLatestVersion(PIPELINE_UUID)).thenReturn(stored);
		when(pipelineDao.loadPage(any(), anyInt(), any(), any(), any())).thenReturn(new Page<>(1, 1, List.of(stored)));

		when(versionDao.createVersion(any(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyInt(), anyBoolean(), any()))
			.thenAnswer(i -> version(i.getArgument(2), i.getArgument(3), i.getArgument(5)));
	}

	private static PipelineVersion version(int number, String name, JsonObject definition) {
		PipelineVersion version = mock(PipelineVersion.class);
		when(version.getUuid()).thenReturn(VERSION_UUID);
		when(version.getVersionNumber()).thenReturn(number);
		when(version.getName()).thenReturn(name);
		when(version.getDefinition()).thenReturn(definition);
		when(version.isEnabled()).thenReturn(true);
		return version;
	}

	/** A source feeding one analysis node — the smallest graph that exercises the port rules. */
	private static JsonObject validDefinition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "pn2").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "media")));
	}

	private static String text(JsonObject result) {
		return result.getJsonArray("content").getJsonObject(0).getString("text");
	}

	// --- validate_pipeline -------------------------------------------------

	@Test
	public void testValidateAcceptsAGoodGraph() {
		String text = text(new ValidatePipelineTool(authoring)
			.execute(new JsonObject().put("definition", validDefinition())).result());
		assertTrue(text.startsWith("VALID"));
	}

	/**
	 * A warning must not read as a refusal: no worker is connected in this test, and the definition is still storable.
	 */
	@Test
	public void testValidateReportsUnplaceableKindsAsWarnings() {
		String text = text(new ValidatePipelineTool(authoring)
			.execute(new JsonObject().put("definition", validDefinition())).result());
		assertTrue(text.startsWith("VALID"));
		assertTrue(text.contains("No online worker currently accepts these node kinds"));
		assertTrue(text.contains("sha512"));
	}

	@Test
	public void testValidateNamesTheOffendingPort() {
		JsonObject broken = validDefinition();
		broken.getJsonArray("edges").getJsonObject(0).put("targetPort", "not_a_port");

		String text = text(new ValidatePipelineTool(authoring).execute(new JsonObject().put("definition", broken)).result());
		assertTrue(text.startsWith("INVALID:"));
		assertTrue(text.contains("not_a_port"), "The message has to name the port or the model cannot fix it");
	}

	@Test
	public void testValidateRejectsUnknownKind() {
		JsonObject broken = validDefinition();
		broken.getJsonArray("nodes").getJsonObject(1).put("type", "no-such-kind");
		assertTrue(text(new ValidatePipelineTool(authoring).execute(new JsonObject().put("definition", broken)).result())
			.contains("Unknown node type"));
	}

	@Test
	public void testValidateMissingDefinitionIsAnErrorResult() {
		assertTrue(text(new ValidatePipelineTool(authoring).execute(new JsonObject()).result())
			.startsWith("ERROR: The definition parameter is required"));
	}

	// --- create_pipeline ---------------------------------------------------

	@Test
	public void testCreateStoresAndRendersTheGraph() {
		JsonObject result = new CreatePipelineTool(authoring, renderer)
			.execute(new JsonObject().put("name", "Checksums").put("definition", validDefinition()), CALLER)
			.result();

		String text = text(result);
		assertTrue(text.startsWith("Created pipeline (version 1)."));
		assertTrue(text.contains("pn1.media -> pn2.media"), "The text must carry the graph — it is all the model sees");

		JsonObject ref = result.getJsonArray("references").getJsonObject(0);
		assertEquals("pipeline", ref.getString("type"));
		assertEquals(PIPELINE_UUID.toString(), ref.getString("uuid"));

		JsonObject visual = result.getJsonArray("visuals").getJsonObject(0);
		assertEquals(PipelineGraphRenderer.VISUAL_TYPE, visual.getString("type"));
		assertEquals(2, visual.getJsonObject("payload").getJsonArray("nodes").size());

		verify(pipelineDao).store(any());
		verify(versionDao).store(any());
	}

	/**
	 * The definition is judged before the first write, so a rejected create leaves no pipeline row behind. Anything else and an agent iterating on a
	 * draft litters the catalog with half-built pipelines.
	 */
	@Test
	public void testCreateStoresNothingWhenTheDefinitionIsRejected() {
		JsonObject broken = validDefinition();
		broken.getJsonArray("edges").getJsonObject(0).put("targetPort", "not_a_port");

		String text = text(new CreatePipelineTool(authoring, renderer)
			.execute(new JsonObject().put("name", "Broken").put("definition", broken), CALLER).result());

		assertTrue(text.startsWith("INVALID:"));
		assertTrue(text.contains("Nothing was stored"));
		verify(pipelineDao, never()).store(any());
		verify(versionDao, never()).store(any());
	}

	@Test
	public void testCreateRequiresNameAndDefinition() {
		CreatePipelineTool tool = new CreatePipelineTool(authoring, renderer);
		assertTrue(text(tool.execute(new JsonObject().put("definition", validDefinition()), CALLER).result())
			.startsWith("ERROR: The name parameter is required."));
		assertTrue(text(tool.execute(new JsonObject().put("name", "x"), CALLER).result())
			.startsWith("ERROR: The definition parameter is required"));
	}

	// --- update_pipeline ---------------------------------------------------

	@Test
	public void testUpdateAppendsAVersion() {
		PipelineVersion latest = version(3, "Checksums", validDefinition());
		when(versionDao.loadLatestByPipeline(PIPELINE_UUID)).thenReturn(latest);

		String text = text(new UpdatePipelineTool(authoring, renderer)
			.execute(new JsonObject()
				.put("pipelineId", PIPELINE_UUID.toString())
				.put("definition", validDefinition()), CALLER)
			.result());

		assertTrue(text.startsWith("Updated pipeline (version 4)."), "An update appends; it never edits a stored version");
	}

	@Test
	public void testUpdateResolvesByName() {
		PipelineVersion latest = version(1, "Checksums", validDefinition());
		when(versionDao.loadLatestByPipeline(PIPELINE_UUID)).thenReturn(latest);

		String text = text(new UpdatePipelineTool(authoring, renderer)
			.execute(new JsonObject().put("pipelineId", "checksums").put("description", "Now with a description"), CALLER)
			.result());
		assertTrue(text.startsWith("Updated pipeline (version 2)."));
	}

	@Test
	public void testUpdateUnknownPipeline() {
		PipelineVersion latest = version(1, "Checksums", validDefinition());
		when(versionDao.loadLatestByPipeline(PIPELINE_UUID)).thenReturn(latest);
		assertTrue(text(new UpdatePipelineTool(authoring, renderer)
			.execute(new JsonObject().put("pipelineId", "no-such-pipeline"), CALLER).result())
			.startsWith("No pipeline found for:"));
	}

	// --- descriptors -------------------------------------------------------

	/**
	 * The write tools declare {@code requiresIdentity}, which is what keeps them off the EventBus, and they require the MCP permission in addition to
	 * the base one so granting the MCP permission alone cannot widen what a user may do.
	 */
	@Test
	public void testWriteToolDescriptors() {
		var create = new CreatePipelineTool(authoring, renderer).descriptor();
		assertTrue(create.requiresIdentity());
		assertEquals(List.of("CREATE_PIPELINE", "CREATE_MCP_PIPELINE"), create.requiredPermissions());
		assertTrue(create.inputSchema().getJsonArray("required").contains("name"));
		assertTrue(create.inputSchema().getJsonArray("required").contains("definition"));

		var update = new UpdatePipelineTool(authoring, renderer).descriptor();
		assertTrue(update.requiresIdentity());
		assertEquals(List.of("UPDATE_PIPELINE", "UPDATE_MCP_PIPELINE"), update.requiredPermissions());

		var validate = new ValidatePipelineTool(authoring).descriptor();
		assertFalse(validate.requiresIdentity(), "A dry run writes nothing and needs no creator");
		assertEquals(List.of("READ_PIPELINE", "VALIDATE_MCP_PIPELINE"), validate.requiredPermissions());

		// requiresIdentity is a server-side dispatch detail and must not reach the wire.
		assertFalse(create.toJson().containsKey("requiresIdentity"));
	}

	/**
	 * Reaching the identity-free overload would mean the caller identity was lost on the way in. It must fail loudly rather than write a pipeline with
	 * no creator.
	 */
	@Test
	public void testIdentityFreeExecuteFailsLoudly() {
		var createResult = new CreatePipelineTool(authoring, renderer).execute(new JsonObject());
		assertTrue(createResult.failed());
		assertTrue(createResult.cause().getMessage().contains("requires an authenticated caller"));

		var updateResult = new UpdatePipelineTool(authoring, renderer).execute(new JsonObject());
		assertTrue(updateResult.failed());
	}

	@Test
	public void testGuideToolServesTheBuiltinSkill() {
		JsonObject result = new PipelineAuthoringGuideTool().execute(new JsonObject()).result();
		String text = text(result);
		assertNotNull(text);
		assertTrue(text.contains("sourcePort"));
		assertTrue(text.contains("validate_pipeline"));
		assertEquals(List.of("READ_PIPELINE"), new PipelineAuthoringGuideTool().descriptor().requiredPermissions());
	}

}
