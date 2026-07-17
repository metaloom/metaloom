package io.metaloom.loom.mcp.handler;

import static io.metaloom.loom.mcp.MCPConstants.*;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.mcp.model.JsonRpcRequest;
import io.metaloom.loom.mcp.model.JsonRpcResponse;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;

/**
 * Handles incoming MCP JSON-RPC 2.0 requests and produces responses.
 *
 * <p>Tool calls are dispatched through the Vert.x EventBus via
 * {@link MCPToolRegistry#dispatch} — this keeps the handler decoupled from
 * individual tool implementations and allows tools to be registered/unregistered
 * at runtime.</p>
 *
 * <p>When a user is authenticated, their permissions are checked before tool
 * execution via {@link MCPToolRegistry#dispatch(String, JsonObject, User)}.</p>
 */
@Singleton
public class MCPJsonRpcHandler {

	private static final Logger log = LoggerFactory.getLogger(MCPJsonRpcHandler.class);

	private final MCPToolRegistry toolRegistry;

	@Inject
	public MCPJsonRpcHandler(MCPToolRegistry toolRegistry) {
		this.toolRegistry = toolRegistry;
	}

	/**
	 * Process an incoming JSON-RPC request and return the response.
	 *
	 * @param request the JSON-RPC request
	 * @param user the authenticated user (may be null if auth is disabled)
	 * @return future with the JSON-RPC response
	 */
	public Future<JsonRpcResponse> handle(JsonRpcRequest request, User user) {
		if (request.getMethod() == null) {
			return Future.succeededFuture(
				JsonRpcResponse.error(request.getId(), ERR_INVALID_REQUEST, "Missing method"));
		}

		log.debug("MCP request: method={} id={} user={}", request.getMethod(), request.getId(), 
			user != null ? user.principal().getString("uuid") : "anonymous");

		return switch (request.getMethod()) {
			case METHOD_INITIALIZE -> handleInitialize(request);
			case METHOD_INITIALIZED -> handleInitialized(request);
			case METHOD_PING -> handlePing(request);
			case METHOD_TOOLS_LIST -> handleToolsList(request);
			case METHOD_TOOLS_CALL -> handleToolsCall(request, user);
			case METHOD_RESOURCES_LIST -> handleResourcesList(request);
			case METHOD_RESOURCES_READ -> handleResourcesRead(request);
			default -> Future.succeededFuture(
				JsonRpcResponse.error(request.getId(), ERR_METHOD_NOT_FOUND,
					"Unknown method: " + request.getMethod()));
		};
	}

	// ---- MCP lifecycle ----

	private Future<JsonRpcResponse> handleInitialize(JsonRpcRequest request) {
		JsonObject result = new JsonObject()
			.put("protocolVersion", MCP_PROTOCOL_VERSION)
			.put("capabilities", new JsonObject()
				.put("tools", new JsonObject()
					.put("listChanged", true))
				.put("resources", new JsonObject()
					.put("subscribe", false)
					.put("listChanged", false)))
			.put("serverInfo", new JsonObject()
				.put("name", "loom-mcp-server")
				.put("version", "1.0.0-SNAPSHOT"));
		return Future.succeededFuture(JsonRpcResponse.success(request.getId(), result));
	}

	private Future<JsonRpcResponse> handleInitialized(JsonRpcRequest request) {
		// Notification — no response needed (but we return empty for consistency in our pipeline)
		log.info("MCP client initialized");
		return Future.succeededFuture(null);
	}

	private Future<JsonRpcResponse> handlePing(JsonRpcRequest request) {
		return Future.succeededFuture(JsonRpcResponse.success(request.getId(), new JsonObject()));
	}

	// ---- Tools ----

	private Future<JsonRpcResponse> handleToolsList(JsonRpcRequest request) {
		JsonArray toolsArray = new JsonArray();
		for (MCPToolDescriptor descriptor : toolRegistry.listDescriptors()) {
			toolsArray.add(descriptor.toJson());
		}
		JsonObject result = new JsonObject().put("tools", toolsArray);
		return Future.succeededFuture(JsonRpcResponse.success(request.getId(), result));
	}

	private Future<JsonRpcResponse> handleToolsCall(JsonRpcRequest request, User user) {
		JsonObject params = request.getParams();
		if (params == null) {
			return Future.succeededFuture(
				JsonRpcResponse.error(request.getId(), ERR_INVALID_PARAMS, "Missing params"));
		}
		String toolName = params.getString("name");
		if (toolName == null) {
			return Future.succeededFuture(
				JsonRpcResponse.error(request.getId(), ERR_INVALID_PARAMS, "Missing tool name"));
		}
		JsonObject arguments = params.getJsonObject("arguments", new JsonObject());

		// Dispatch via EventBus with permission checking
		return toolRegistry.dispatch(toolName, arguments, user)
			.map(toolResult -> JsonRpcResponse.success(request.getId(), toolResult))
			.recover(err -> {
				log.error("Tool call failed: {}", toolName, err);
				return Future.succeededFuture(
					JsonRpcResponse.error(request.getId(), ERR_INTERNAL, err.getMessage()));
			});
	}

	// ---- Resources (stubbed for future implementation) ----

	private Future<JsonRpcResponse> handleResourcesList(JsonRpcRequest request) {
		// Return empty resource list for now — will be populated when resource providers are added
		JsonObject result = new JsonObject().put("resources", new JsonArray());
		return Future.succeededFuture(JsonRpcResponse.success(request.getId(), result));
	}

	private Future<JsonRpcResponse> handleResourcesRead(JsonRpcRequest request) {
		return Future.succeededFuture(
			JsonRpcResponse.error(request.getId(), ERR_METHOD_NOT_FOUND, "Resource reading not yet implemented"));
	}

}
