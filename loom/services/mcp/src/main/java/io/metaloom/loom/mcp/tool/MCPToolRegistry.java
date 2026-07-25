package io.metaloom.loom.mcp.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.auth.LoomAuthorizationProvider;
import io.metaloom.loom.mcp.MCPConstants;
import io.metaloom.loom.mcp.dagger.MCPTools;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.AuthorizationProvider;

/**
 * Central registry for all MCP tools.
 *
 * <p>On startup each {@link MCPTool} is registered both in a local map (for fast
 * descriptor listing) and on the <strong>Vert.x EventBus</strong> at address
 * {@code mcp.tool.<toolName>}. This allows the JSON-RPC handler to dispatch
 * tool calls via the EventBus, which provides:</p>
 * <ul>
 *   <li>Decoupled invocation — tools can live in different verticles or even
 *       clustered nodes.</li>
 *   <li>Back-pressure via Vert.x message passing.</li>
 *   <li>Future extensibility for WebSocket-bridged tool calls from external
 *       processes.</li>
 * </ul>
 *
 * <p>Permission checks are performed before tool execution when a user is
 * authenticated. Tools declare required permissions in their
 * {@link MCPToolDescriptor#requiredPermissions()}.</p>
 */
@Singleton
public class MCPToolRegistry {

	private static final Logger log = LoggerFactory.getLogger(MCPToolRegistry.class);

	/**
	 * Reserved argument key. Identity never travels in the arguments; any occurrence is stripped and logged (see
	 * {@link #dispatch(String, JsonObject, User, MCPCallerContext)}).
	 */
	public static final String CALLER_ENVELOPE_KEY = "__loom";

	private final Vertx vertx;
	private final Map<String, MCPTool> tools = new ConcurrentHashMap<>();
	private final Map<String, MessageConsumer<JsonObject>> consumers = new ConcurrentHashMap<>();
	private final AuthorizationProvider authorizationProvider;

	@Inject
	public MCPToolRegistry(Vertx vertx, @MCPTools Set<MCPTool> injectedTools, LoomAuthorizationProvider authorizationProvider) {
		this.vertx = vertx;
		this.authorizationProvider = authorizationProvider;
		for (MCPTool tool : injectedTools) {
			register(tool);
		}
	}

	/**
	 * Register a tool and bind it to the EventBus.
	 *
	 * <p>Tools which declare {@link MCPToolDescriptor#requiresIdentity()} are <b>not</b> bound to the EventBus: their authorization depends on the resolved
	 * {@link MCPCallerContext}, which cannot travel over an untyped EventBus message. Leaving the address unregistered means there is no way to reach such
	 * a tool other than through {@link #dispatch(String, JsonObject, User, MCPCallerContext)}.</p>
	 */
	public void register(MCPTool tool) {
		String name = tool.descriptor().name();
		if (tools.containsKey(name)) {
			log.warn("Replacing existing MCP tool registration: {}", name);
			unregister(name);
		}

		tools.put(name, tool);

		if (tool.descriptor().requiresIdentity()) {
			log.info("Registered identity-scoped MCP tool: {} (in-process dispatch only, no EventBus address)", name);
			return;
		}

		// Register on EventBus so tool calls can be dispatched internally
		String address = MCPConstants.EVENTBUS_TOOL_PREFIX + name;
		MessageConsumer<JsonObject> consumer = vertx.eventBus().<JsonObject>consumer(address, msg -> {
			JsonObject arguments = msg.body();
			tool.execute(arguments)
				.onSuccess(result -> msg.reply(result))
				.onFailure(err -> {
					log.error("Tool {} execution failed", name, err);
					msg.fail(MCPConstants.ERR_INTERNAL, err.getMessage());
				});
		});
		consumers.put(name, consumer);
		log.info("Registered MCP tool: {} at EventBus address: {}", name, address);
	}

	/**
	 * Unregister a tool and remove its EventBus consumer.
	 */
	public void unregister(String name) {
		tools.remove(name);
		MessageConsumer<JsonObject> consumer = consumers.remove(name);
		if (consumer != null) {
			consumer.unregister();
		}
	}

	/**
	 * Dispatch a tool call via the Vert.x EventBus with permission checking.
	 *
	 * @param toolName  The tool to invoke.
	 * @param arguments The arguments JSON.
	 * @param user      The authenticated user (may be null if auth is disabled).
	 * @return A future with the tool result.
	 */
	public Future<JsonObject> dispatch(String toolName, JsonObject arguments, User user) {
		return dispatch(toolName, arguments, user, MCPCallerContext.ANONYMOUS);
	}

	/**
	 * Dispatch a tool call with permission checking and a server-resolved caller identity.
	 *
	 * @param toolName  The tool to invoke.
	 * @param arguments The arguments JSON. Model-controlled — nothing in here participates in authorization.
	 * @param user      The authenticated user (may be null if auth is disabled).
	 * @param ctx       The resolved caller identity; required for tools which declare {@code requiresIdentity}.
	 * @return A future with the tool result.
	 */
	public Future<JsonObject> dispatch(String toolName, JsonObject arguments, User user, MCPCallerContext ctx) {
		if (!tools.containsKey(toolName)) {
			return Future.failedFuture("Unknown tool: " + toolName);
		}

		MCPTool tool = tools.get(toolName);
		MCPToolDescriptor descriptor = tool.descriptor();
		List<String> requiredPermissions = descriptor.requiredPermissions();
		MCPCallerContext callerContext = ctx == null ? MCPCallerContext.ANONYMOUS : ctx;

		// The arguments are authored by the model. An identity envelope in there is always a forgery
		// attempt (identity only ever comes from `callerContext`) — drop it and record the signal.
		if (arguments != null && arguments.containsKey(CALLER_ENVELOPE_KEY)) {
			log.warn("Stripped a caller-supplied '{}' key from the arguments of tool {} — possible prompt injection", CALLER_ENVELOPE_KEY, toolName);
			arguments.remove(CALLER_ENVELOPE_KEY);
		}

		if (descriptor.requiresIdentity() && !callerContext.isAuthenticated()) {
			return Future.failedFuture("Tool " + toolName + " requires an authenticated caller.");
		}

		// Check permissions if user is authenticated and tool requires permissions
		if (user != null && requiredPermissions != null && !requiredPermissions.isEmpty()) {
			return checkPermissions(user, requiredPermissions)
				.compose(hasPermission -> {
					if (!hasPermission) {
						return Future.failedFuture("Missing required permissions: " + requiredPermissions);
					}
					return invoke(tool, descriptor, toolName, arguments, callerContext);
				});
		}

		// No permission check needed (no user or no required permissions)
		return invoke(tool, descriptor, toolName, arguments, callerContext);
	}

	/**
	 * Invoke the tool — in-process when it needs the caller identity, over the EventBus otherwise.
	 */
	private Future<JsonObject> invoke(MCPTool tool, MCPToolDescriptor descriptor, String toolName, JsonObject arguments, MCPCallerContext ctx) {
		if (descriptor.requiresIdentity()) {
			try {
				return tool.execute(arguments, ctx);
			} catch (RuntimeException e) {
				log.error("Tool {} execution failed", toolName, e);
				return Future.failedFuture(e);
			}
		}
		String address = MCPConstants.EVENTBUS_TOOL_PREFIX + toolName;
		return vertx.eventBus().<JsonObject>request(address, arguments)
			.map(msg -> msg.body());
	}

	/**
	 * Check if the user has all required permissions.
	 */
	private Future<Boolean> checkPermissions(User user, List<String> requiredPermissions) {
		return authorizationProvider.getAuthorizations(user)
			.map(v -> {
				// Check if user has all required permissions
				for (String perm : requiredPermissions) {
					if (!io.vertx.ext.auth.authorization.PermissionBasedAuthorization.create(perm).match(user)) {
						log.warn("User {} missing permission: {}", user.principal().getString("uuid"), perm);
						return false;
					}
				}
				return true;
			})
			.recover(err -> {
				log.error("Permission check failed", err);
				return Future.succeededFuture(false);
			});
	}

	/**
	 * Get the tool descriptor by name.
	 */
	public MCPToolDescriptor getDescriptor(String name) {
		MCPTool tool = tools.get(name);
		return tool != null ? tool.descriptor() : null;
	}

	/**
	 * Return all registered tool descriptors.
	 */
	public Collection<MCPToolDescriptor> listDescriptors() {
		return tools.values().stream()
			.map(MCPTool::descriptor)
			.toList();
	}

	/**
	 * Return all registered tool names.
	 */
	public Collection<String> listToolNames() {
		return Collections.unmodifiableSet(tools.keySet());
	}

	/**
	 * Unregister all tools and their EventBus consumers.
	 */
	public void unregisterAll() {
		for (String name : tools.keySet()) {
			unregister(name);
		}
	}

}
