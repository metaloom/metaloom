package io.metaloom.loom.mcp.tool;

import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * Interface for an MCP tool that can be registered with the server.
 *
 * <p>Each tool implementation provides a descriptor (name, description, input schema)
 * and a handler that processes invocation requests and returns a result.</p>
 *
 * <p>Tool implementations are registered on the Vert.x EventBus at
 * {@code mcp.tool.<name>} so they can be dispatched internally from the
 * MCP JSON-RPC handler.</p>
 */
public interface MCPTool {

	/**
	 * Return the descriptor for this tool, including its JSON Schema for parameters.
	 */
	MCPToolDescriptor descriptor();

	/**
	 * Execute the tool with the given arguments.
	 *
	 * @param arguments The arguments as provided by the MCP client (validated against inputSchema).
	 * @return A future resolving to the tool result as a JsonObject with MCP content items.
	 */
	Future<JsonObject> execute(JsonObject arguments);

}
