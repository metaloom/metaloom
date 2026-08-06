package io.metaloom.loom.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.metaloom.loom.test.data.TestValues;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;

/**
 * Pipeline authoring over MCP, end to end against a real PostgreSQL database.
 *
 * <p>
 * The unit tests cover the rendering and the rules; what only a real database can answer is whether a pipeline the agent authored actually lands —
 * {@code pipeline} row, {@code pipeline_version} row, and a {@code latest_version_uuid} that points at it — and whether an unauthorised caller is kept
 * out of the write tools in both directions: not listed, and refused if called anyway.
 * </p>
 *
 * <p>
 * Run {@code ./setup-pool.sh} first.
 * </p>
 */
public class MCPPipelineAuthoringTest implements TestValues {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private MCPToolRegistry registry() {
		return loom.internal().boot().getMcpService().getToolRegistry();
	}

	/**
	 * The bootstrap admin holds every permission, so this is the "fully privileged agent" case.
	 */
	private MCPCallerContext adminContext() {
		return new MCPCallerContext(TestValues.ADMIN_UUID, "admin", Set.of(), null, null);
	}

	private User adminUser() {
		return User.create(new JsonObject().put("uuid", TestValues.ADMIN_UUID.toString()));
	}

	/** A correctly identified caller who holds no permissions at all. */
	private User unprivilegedUser() {
		return User.create(new JsonObject().put("uuid", UUID.randomUUID().toString()));
	}

	private JsonObject dispatch(String tool, JsonObject args, User user, MCPCallerContext ctx) throws Exception {
		return await(registry().dispatch(tool, args, user, ctx));
	}

	private static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
	}

	private static String text(JsonObject result) {
		return result.getJsonArray("content").getJsonObject(0).getString("text");
	}

	/** A source feeding one analysis node, wired port to port. */
	private static JsonObject definition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "pn2").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "media")));
	}

	// --- discovery ---------------------------------------------------------

	@Test
	public void testNodeDescriptorsAreDiscoverable() throws Exception {
		String listed = text(dispatch("list_node_descriptors", new JsonObject(), null, MCPCallerContext.ANONYMOUS));
		assertTrue(listed.contains("filesystem-source"));
		assertTrue(listed.contains("[SOURCE]"));

		String detail = text(dispatch("get_node_descriptor", new JsonObject().put("kind", "sha512"), null, MCPCallerContext.ANONYMOUS));
		assertTrue(detail.contains("- media : media/*"), detail);
		assertTrue(detail.contains("- hash : hash/sha512"), detail);
	}

	@Test
	public void testAuthoringGuideIsServed() throws Exception {
		String guide = text(dispatch("pipeline_authoring_guide", new JsonObject(), null, MCPCallerContext.ANONYMOUS));
		assertTrue(guide.contains("sourcePort"));
		assertTrue(guide.contains("Exactly one source"));
	}

	// --- validate ----------------------------------------------------------

	@Test
	public void testValidateAgainstTheRealDescriptorRegistry() throws Exception {
		assertTrue(text(dispatch("validate_pipeline", new JsonObject().put("definition", definition()), null, MCPCallerContext.ANONYMOUS))
			.startsWith("VALID"));

		JsonObject broken = definition();
		broken.getJsonArray("edges").getJsonObject(0).put("targetPort", "not_a_port");
		String rejected = text(dispatch("validate_pipeline", new JsonObject().put("definition", broken), null, MCPCallerContext.ANONYMOUS));
		assertTrue(rejected.startsWith("INVALID:"), rejected);
		assertTrue(rejected.contains("not_a_port"), rejected);
	}

	// --- create / update ---------------------------------------------------

	@Test
	public void testCreatePersistsPipelineAndVersion() throws Exception {
		String name = "mcp-authored-" + UUID.randomUUID();
		JsonObject result = dispatch("create_pipeline", new JsonObject()
			.put("name", name)
			.put("description", "Authored over MCP")
			.put("definition", definition()), adminUser(), adminContext());

		assertTrue(text(result).startsWith("Created pipeline (version 1)."), text(result));

		String uuid = result.getJsonArray("references").getJsonObject(0).getString("uuid");
		Pipeline stored = loom.internal().daos().pipelineDao().loadWithLatestVersion(UUID.fromString(uuid));
		assertNotNull(stored, "The pipeline row must exist");

		PipelineVersion version = loom.internal().daos().pipelineVersionDao().loadLatestByPipeline(stored.getUuid());
		assertNotNull(version, "Version 1 must exist");
		assertEquals(1, version.getVersionNumber());
		assertEquals(name, version.getName());
		assertEquals(stored.getLatestVersionUuid(), version.getUuid(), "latest_version_uuid must point at the version just written");
		// Stamped on the way in, so the stored definition names its own format.
		assertEquals(1, version.getDefinition().getInteger("version"));

		// And the pipeline reads back through the ordinary read tool.
		assertTrue(text(dispatch("get_pipeline", new JsonObject().put("pipelineId", uuid), adminUser(), adminContext()))
			.contains("pn1.media -> pn2.media"));
	}

	@Test
	public void testCreateWithABrokenDefinitionStoresNothing() throws Exception {
		String name = "mcp-rejected-" + UUID.randomUUID();
		JsonObject broken = definition();
		broken.getJsonArray("edges").getJsonObject(0).put("targetPort", "not_a_port");

		JsonObject result = dispatch("create_pipeline", new JsonObject().put("name", name).put("definition", broken),
			adminUser(), adminContext());
		assertTrue(text(result).startsWith("INVALID:"), text(result));
		assertNull(result.getJsonArray("references"), "A rejected create references nothing");

		// Nothing is findable under that name — a rejected definition must not leave a row behind.
		assertTrue(text(dispatch("get_pipeline", new JsonObject().put("pipelineId", name), adminUser(), adminContext()))
			.startsWith("No pipeline found for:"));
	}

	@Test
	public void testUpdateAppendsAVersion() throws Exception {
		String name = "mcp-updated-" + UUID.randomUUID();
		JsonObject created = dispatch("create_pipeline", new JsonObject().put("name", name).put("definition", definition()),
			adminUser(), adminContext());
		String uuid = created.getJsonArray("references").getJsonObject(0).getString("uuid");

		JsonObject updated = dispatch("update_pipeline", new JsonObject()
			.put("pipelineId", name)
			.put("description", "Second thoughts"), adminUser(), adminContext());
		assertTrue(text(updated).startsWith("Updated pipeline (version 2)."), text(updated));

		List<PipelineVersion> versions = loom.internal().daos().pipelineVersionDao().loadByPipeline(UUID.fromString(uuid));
		assertEquals(2, versions.size(), "An update appends a version; it never edits the stored one");
	}

	// --- permissions -------------------------------------------------------

	/**
	 * Not advertised and not callable are two separate guarantees. A caller who is told about a tool they cannot use wastes a turn on it; a caller who
	 * is merely not told about it is still one hand-written JSON-RPC frame away from calling it.
	 */
	@Test
	public void testUnprivilegedCallerIsNeitherToldNorAllowed() throws Exception {
		User user = unprivilegedUser();

		List<String> names = await(registry().listDescriptorsFor(user)).stream().map(MCPToolDescriptor::name).toList();
		assertFalse(names.contains("create_pipeline"), "A caller without the permission must not be told about the write tool");
		assertFalse(names.contains("update_pipeline"));
		assertFalse(names.contains("validate_pipeline"));

		MCPCallerContext ctx = new MCPCallerContext(UUID.fromString(user.principal().getString("uuid")), "nobody", Set.of(), null, null);
		Throwable failure = failureOf(registry().dispatch("create_pipeline", new JsonObject()
			.put("name", "should-not-exist").put("definition", definition()), user, ctx));
		assertNotNull(failure, "The call itself must be refused, not merely hidden");
		assertTrue(failure.getMessage().contains("Missing required permissions"), failure.getMessage());
	}

	@Test
	public void testAdminIsToldAboutTheAuthoringTools() throws Exception {
		List<String> names = await(registry().listDescriptorsFor(adminUser())).stream().map(MCPToolDescriptor::name).toList();
		assertTrue(names.contains("create_pipeline"));
		assertTrue(names.contains("update_pipeline"));
		assertTrue(names.contains("validate_pipeline"));
		assertTrue(names.contains("list_node_descriptors"));
		assertTrue(names.contains("get_node_descriptor"));
		assertTrue(names.contains("pipeline_authoring_guide"));
	}

	/**
	 * The write tools are identity-scoped, so an anonymous caller is refused before any permission check runs — that refusal is the reason they carry
	 * no EventBus address.
	 */
	@Test
	public void testAnonymousCallerCannotAuthor() throws Exception {
		Throwable failure = failureOf(registry().dispatch("create_pipeline", new JsonObject()
			.put("name", "anonymous").put("definition", definition()), null, MCPCallerContext.ANONYMOUS));
		assertNotNull(failure);
		assertTrue(failure.getMessage().contains("requires an authenticated caller"), failure.getMessage());
	}

	private static Throwable failureOf(Future<JsonObject> future) throws Exception {
		try {
			future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
			return null;
		} catch (java.util.concurrent.ExecutionException e) {
			return e.getCause();
		}
	}

}
