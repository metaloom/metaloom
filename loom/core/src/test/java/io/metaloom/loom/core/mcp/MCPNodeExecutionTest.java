package io.metaloom.loom.core.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.metaloom.loom.test.data.TestValues;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;

/**
 * The four ad-hoc execution tools, registered against the real MCP registry.
 *
 * <p>
 * The unit tests cover what each tool renders. What only the real registry can answer is whether the
 * tools are actually wired in, and whether a caller without {@code EXECUTE_MCP_NODE} is kept out in
 * <b>both</b> directions - not listed, and refused if called anyway. These are separate guarantees:
 * a caller who is told about a tool they cannot use wastes a turn on it, and a caller who is merely
 * not told about it is still one hand-written JSON-RPC frame away from spending worker time.
 * </p>
 *
 * <p>
 * Run {@code ./setup-pool.sh} first.
 * </p>
 */
public class MCPNodeExecutionTest implements TestValues {

	private static final List<String> EXECUTION_TOOLS = List.of("run_node_probe", "run_node_graph", "get_job", "cancel_job");

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private MCPToolRegistry registry() {
		return loom.internal().boot().getMcpService().getToolRegistry();
	}

	private User adminUser() {
		return User.create(new JsonObject().put("uuid", TestValues.ADMIN_UUID.toString()));
	}

	private User unprivilegedUser() {
		return User.create(new JsonObject().put("uuid", UUID.randomUUID().toString()));
	}

	@Test
	public void testAdminIsToldAboutTheExecutionTools() throws Exception {
		List<String> names = await(registry().listDescriptorsFor(adminUser())).stream().map(MCPToolDescriptor::name).toList();
		assertThat(names).containsAll(EXECUTION_TOOLS);
	}

	@Test
	public void testUnprivilegedCallerIsNeitherToldNorAllowed() throws Exception {
		User user = unprivilegedUser();

		List<String> names = await(registry().listDescriptorsFor(user)).stream().map(MCPToolDescriptor::name).toList();
		for (String tool : EXECUTION_TOOLS) {
			assertFalse(names.contains(tool), "A caller without EXECUTE_MCP_NODE must not be told about " + tool);
		}

		MCPCallerContext ctx = new MCPCallerContext(UUID.fromString(user.principal().getString("uuid")), "nobody", Set.of(), null, null);
		Throwable failure = failureOf(registry().dispatch("run_node_probe", new JsonObject()
			.put("kind", "sha512").put("assetUuid", UUID.randomUUID().toString()), user, ctx));
		assertNotNull(failure, "The call itself must be refused, not merely hidden");
		assertTrue(failure.getMessage().contains("Missing required permissions"), failure.getMessage());
	}

	/**
	 * The execution tools are identity-scoped, so an anonymous caller is refused before any permission
	 * check runs. That refusal is exactly why they carry no EventBus address: with MCP authentication
	 * disabled, an external client is anonymous and must not be able to occupy the GPU fleet.
	 */
	@Test
	public void testAnonymousCallerCannotExecute() throws Exception {
		for (String tool : EXECUTION_TOOLS) {
			Throwable failure = failureOf(registry().dispatch(tool, new JsonObject()
				.put("kind", "sha512")
				.put("assetUuid", UUID.randomUUID().toString())
				.put("jobId", UUID.randomUUID().toString())
				.put("definition", new JsonObject())
				.put("assetUuids", new JsonArray().add(UUID.randomUUID().toString())),
				null, MCPCallerContext.ANONYMOUS));
			assertNotNull(failure, tool + " must refuse an anonymous caller");
			assertTrue(failure.getMessage().contains("requires an authenticated caller"), failure.getMessage());
		}
	}

	private static Throwable failureOf(Future<JsonObject> future) throws Exception {
		try {
			future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
			return null;
		} catch (java.util.concurrent.ExecutionException e) {
			return e.getCause();
		}
	}

	private static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
	}

}
