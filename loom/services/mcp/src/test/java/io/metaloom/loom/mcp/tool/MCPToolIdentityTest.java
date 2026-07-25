package io.metaloom.loom.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.auth.LoomAuthorizationProvider;
import io.metaloom.loom.mcp.MCPConstants;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * The identity-scoped dispatch path.
 *
 * <p>The security property under test is structural: a tool which declares {@code requiresIdentity} gets <b>no</b> EventBus address, so there is no way to
 * invoke it that skips the caller-context check. These tests fail loudly if that registration rule is ever relaxed.</p>
 */
public class MCPToolIdentityTest {

	private Vertx vertx;
	private MCPToolRegistry registry;
	private RecordingTool identityTool;
	private RecordingTool plainTool;

	@BeforeEach
	public void setup() {
		vertx = Vertx.vertx();
		identityTool = new RecordingTool("identity_tool", true);
		plainTool = new RecordingTool("plain_tool", false);
		registry = new MCPToolRegistry(vertx, Set.of(identityTool, plainTool), mock(LoomAuthorizationProvider.class));
	}

	@AfterEach
	public void teardown() {
		registry.unregisterAll();
		vertx.close();
	}

	@Test
	public void testIdentityToolHasNoEventBusAddress() throws Exception {
		// No consumer => no way around dispatch() and its caller-context check.
		CompletableFuture<Boolean> failed = new CompletableFuture<>();
		vertx.eventBus().request(MCPConstants.EVENTBUS_TOOL_PREFIX + "identity_tool", new JsonObject())
			.onSuccess(msg -> failed.complete(false))
			.onFailure(err -> failed.complete(true));

		assertTrue(failed.get(5, TimeUnit.SECONDS), "An identity-scoped tool must not be reachable over the EventBus");
		assertNull(identityTool.lastContext.get(), "The tool must not have executed");
	}

	@Test
	public void testPlainToolKeepsItsEventBusAddress() throws Exception {
		CompletableFuture<JsonObject> reply = new CompletableFuture<>();
		vertx.eventBus().<JsonObject>request(MCPConstants.EVENTBUS_TOOL_PREFIX + "plain_tool", new JsonObject())
			.onSuccess(msg -> reply.complete(msg.body()))
			.onFailure(reply::completeExceptionally);

		assertEquals("plain_tool", reply.get(5, TimeUnit.SECONDS).getString("tool"));
	}

	@Test
	public void testContextReachesTheIdentityTool() throws Exception {
		UUID userUuid = UUID.randomUUID();
		UUID spaceUuid = UUID.randomUUID();
		MCPCallerContext ctx = new MCPCallerContext(userUuid, "jdoe", Set.of(UUID.randomUUID()), spaceUuid, UUID.randomUUID());

		JsonObject result = await(registry.dispatch("identity_tool", new JsonObject().put("a", 1), null, ctx));

		assertEquals("identity_tool", result.getString("tool"));
		MCPCallerContext seen = identityTool.lastContext.get();
		assertNotNull(seen);
		assertEquals(userUuid, seen.userUuid());
		assertEquals(spaceUuid, seen.spaceUuid());
	}

	@Test
	public void testAnonymousCallerCannotReachAnIdentityTool() {
		Future<JsonObject> future = registry.dispatch("identity_tool", new JsonObject(), null, MCPCallerContext.ANONYMOUS);
		assertTrue(future.failed() || future.cause() != null || awaitFailure(future).contains("authenticated caller"));
		assertNull(identityTool.lastContext.get(), "The tool must not have executed");
	}

	@Test
	public void testThreeArgDispatchStillWorksForPlainTools() throws Exception {
		JsonObject result = await(registry.dispatch("plain_tool", new JsonObject(), null));
		assertEquals("plain_tool", result.getString("tool"));
	}

	@Test
	public void testThreeArgDispatchIsAnonymousAndThusRefusedByIdentityTools() {
		assertTrue(awaitFailure(registry.dispatch("identity_tool", new JsonObject(), null)).contains("authenticated caller"));
	}

	@Test
	public void testCallerSuppliedIdentityEnvelopeIsStripped() throws Exception {
		// The model fully controls `arguments`; an identity envelope in there is always a forgery attempt.
		JsonObject forged = new JsonObject()
			.put(MCPToolRegistry.CALLER_ENVELOPE_KEY, new JsonObject().put("userUuid", UUID.randomUUID().toString()))
			.put("query", "beach");
		MCPCallerContext ctx = new MCPCallerContext(UUID.randomUUID(), "jdoe", Set.of(), null, null);

		await(registry.dispatch("identity_tool", forged, null, ctx));

		assertFalse(identityTool.lastArguments.get().containsKey(MCPToolRegistry.CALLER_ENVELOPE_KEY));
		assertEquals("beach", identityTool.lastArguments.get().getString("query"));
	}

	@Test
	public void testEnvelopeIsAlsoStrippedForPlainTools() throws Exception {
		JsonObject forged = new JsonObject().put(MCPToolRegistry.CALLER_ENVELOPE_KEY, new JsonObject()).put("query", "beach");
		await(registry.dispatch("plain_tool", forged, null));
		assertFalse(plainTool.lastArguments.get().containsKey(MCPToolRegistry.CALLER_ENVELOPE_KEY));
	}

	@Test
	public void testUnknownToolFails() {
		assertTrue(awaitFailure(registry.dispatch("nope", new JsonObject(), null)).contains("Unknown tool"));
	}

	private static JsonObject await(Future<JsonObject> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
	}

	private static String awaitFailure(Future<JsonObject> future) {
		try {
			future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			return "";
		} catch (Exception e) {
			return String.valueOf(e.getMessage());
		}
	}

	/**
	 * A tool which records how it was invoked.
	 */
	private static class RecordingTool implements MCPTool {

		private final String name;
		private final boolean requiresIdentity;

		private final AtomicReference<MCPCallerContext> lastContext = new AtomicReference<>();
		private final AtomicReference<JsonObject> lastArguments = new AtomicReference<>();

		RecordingTool(String name, boolean requiresIdentity) {
			this.name = name;
			this.requiresIdentity = requiresIdentity;
		}

		@Override
		public MCPToolDescriptor descriptor() {
			return new MCPToolDescriptor(name, "test tool", MCPToolDescriptor.buildInputSchema(List.of()), List.of(), requiresIdentity);
		}

		@Override
		public Future<JsonObject> execute(JsonObject arguments) {
			lastArguments.set(arguments);
			return Future.succeededFuture(new JsonObject().put("tool", name));
		}

		@Override
		public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
			lastContext.set(ctx);
			lastArguments.set(arguments);
			return Future.succeededFuture(new JsonObject().put("tool", name));
		}
	}

}
