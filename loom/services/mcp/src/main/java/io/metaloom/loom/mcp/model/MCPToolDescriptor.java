package io.metaloom.loom.mcp.model;

import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Describes an MCP tool that can be invoked by clients.
 *
 * @param name               Unique tool name
 * @param description        Human-readable description of what the tool does
 * @param inputSchema        JSON Schema object describing the tool's parameters
 * @param requiredPermissions List of permissions required to invoke this tool (e.g., ["READ_ASSET"])
 */
public record MCPToolDescriptor(String name, String description, JsonObject inputSchema, List<String> requiredPermissions) {

	/**
	 * Convert to the JSON representation expected by the MCP tools/list response.
	 */
	public JsonObject toJson() {
		JsonObject json = new JsonObject()
			.put("name", name)
			.put("description", description)
			.put("inputSchema", inputSchema);
		if (requiredPermissions != null && !requiredPermissions.isEmpty()) {
			json.put("requiredPermissions", new JsonArray(requiredPermissions));
		}
		return json;
	}

	/**
	 * Helper to build a JSON Schema for tool parameters.
	 */
	public static JsonObject buildInputSchema(List<MCPToolParam> params) {
		JsonObject properties = new JsonObject();
		List<String> required = new java.util.ArrayList<>();
		for (MCPToolParam param : params) {
			JsonObject prop = new JsonObject().put("type", param.type()).put("description", param.description());
			properties.put(param.name(), prop);
			if (param.required()) {
				required.add(param.name());
			}
		}
		JsonObject schema = new JsonObject()
			.put("type", "object")
			.put("properties", properties);
		if (!required.isEmpty()) {
			schema.put("required", required);
		}
		return schema;
	}

	public record MCPToolParam(String name, String type, String description, boolean required) {
	}
}
