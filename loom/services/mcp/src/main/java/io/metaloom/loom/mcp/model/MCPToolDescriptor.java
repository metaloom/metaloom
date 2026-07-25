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
 * @param requiresIdentity   Whether the tool needs the resolved {@link MCPCallerContext}. Such tools are dispatched in-process instead of over the EventBus
 *                           (see {@code MCPToolRegistry}), so there is no EventBus address through which the identity check could be bypassed.
 */
public record MCPToolDescriptor(String name, String description, JsonObject inputSchema, List<String> requiredPermissions, boolean requiresIdentity) {

	/**
	 * Convenience constructor for tools which do not need the caller identity.
	 */
	public MCPToolDescriptor(String name, String description, JsonObject inputSchema, List<String> requiredPermissions) {
		this(name, description, inputSchema, requiredPermissions, false);
	}

	/**
	 * Convert to the JSON representation expected by the MCP tools/list response.
	 *
	 * <p>{@code requiresIdentity} is deliberately <b>not</b> serialized — it is a server-side dispatch detail and the wire format must stay unchanged for
	 * external MCP clients.</p>
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
			if (param.enumValues() != null && !param.enumValues().isEmpty()) {
				prop.put("enum", new JsonArray(param.enumValues()));
			}
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

	/**
	 * A single tool parameter.
	 *
	 * @param enumValues
	 *            Optional closed set of accepted values, emitted as JSON Schema {@code enum}. Null or empty means unconstrained.
	 */
	public record MCPToolParam(String name, String type, String description, boolean required, List<String> enumValues) {

		public MCPToolParam(String name, String type, String description, boolean required) {
			this(name, type, description, required, null);
		}
	}
}
