package io.metaloom.loom.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.auth.LoomAuthorizationProvider;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;

/**
 * What a caller is <b>told</b> about must match what they are <b>allowed</b> to do.
 *
 * <p>
 * {@code MCPToolRegistry.dispatch} has always refused a call the caller has no permission for, but the tool list was unconditional — so an agent loop
 * built its prompt from tools it would then be refused on. These tests pin the listing side of that.
 * </p>
 */
public class MCPToolPermissionTest {

	private Vertx vertx;

	private MCPToolRegistry registry;

	private static final MCPToolDescriptor OPEN = new MCPToolDescriptor("open_tool", "Needs nothing",
		MCPToolDescriptor.buildInputSchema(List.of()), List.of());

	private static final MCPToolDescriptor READ = new MCPToolDescriptor("read_tool", "Needs READ_PIPELINE",
		MCPToolDescriptor.buildInputSchema(List.of()), List.of("READ_PIPELINE"));

	private static final MCPToolDescriptor WRITE = new MCPToolDescriptor("write_tool", "Needs two permissions",
		MCPToolDescriptor.buildInputSchema(List.of()), List.of("CREATE_PIPELINE", "CREATE_MCP_PIPELINE"), true);

	@BeforeEach
	public void setup() {
		vertx = Vertx.vertx();
		LoomAuthorizationProvider authorizationProvider = mock(LoomAuthorizationProvider.class);
		// The real provider loads the user's permissions and puts them on the user; the test users
		// below carry theirs already, so resolving is a no-op that must still succeed.
		when(authorizationProvider.getAuthorizations(any())).thenReturn(Future.succeededFuture());
		registry = new MCPToolRegistry(vertx, Set.of(tool(OPEN), tool(READ), tool(WRITE)), authorizationProvider);
	}

	@AfterEach
	public void teardown() {
		registry.unregisterAll();
		vertx.close();
	}

	@Test
	public void testNullUserSeesEverything() {
		// No authenticated caller means no permission check on dispatch either — the two must agree,
		// otherwise a deployment with auth disabled would advertise nothing and still work fine.
		assertEquals(3, names(registry.listDescriptorsFor(null)).size());
	}

	@Test
	public void testOnlyPermittedToolsAreListed() {
		List<String> names = names(registry.listDescriptorsFor(userWith("READ_PIPELINE")));
		assertTrue(names.contains("open_tool"), "A tool that requires nothing is always listed");
		assertTrue(names.contains("read_tool"));
		assertFalse(names.contains("write_tool"));
	}

	/**
	 * The authoring tools declare the base permission <b>and</b> the MCP one. Holding one of the two is not enough — granting the MCP permission alone
	 * must never widen what a user can do.
	 */
	@Test
	public void testAllDeclaredPermissionsAreRequired() {
		assertFalse(names(registry.listDescriptorsFor(userWith("CREATE_PIPELINE"))).contains("write_tool"));
		assertFalse(names(registry.listDescriptorsFor(userWith("CREATE_MCP_PIPELINE"))).contains("write_tool"));
		assertTrue(names(registry.listDescriptorsFor(userWith("CREATE_PIPELINE", "CREATE_MCP_PIPELINE"))).contains("write_tool"));
	}

	@Test
	public void testUserWithoutPermissionsSeesOnlyOpenTools() {
		assertEquals(List.of("open_tool"), names(registry.listDescriptorsFor(userWith())));
	}

	private static List<String> names(Future<List<MCPToolDescriptor>> future) {
		try {
			return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).stream()
				.map(MCPToolDescriptor::name)
				.sorted()
				.toList();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * A user whose authorizations are already resolved, which is the state the registry works against once {@code getAuthorizations} has run.
	 */
	private static User userWith(String... permissions) {
		User user = User.fromName("tester");
		user.principal().put("uuid", "00000000-0000-0000-0000-000000000001");
		Set<Authorization> authorizations = new HashSet<>();
		for (String permission : permissions) {
			authorizations.add(PermissionBasedAuthorization.create(permission));
		}
		user.authorizations().put("loom", authorizations);
		return user;
	}

	private static MCPTool tool(MCPToolDescriptor descriptor) {
		return new MCPTool() {

			@Override
			public MCPToolDescriptor descriptor() {
				return descriptor;
			}

			@Override
			public Future<JsonObject> execute(JsonObject arguments) {
				return Future.succeededFuture(new JsonObject());
			}
		};
	}

}
