package io.metaloom.loom.mcp.tool;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Helpers for building MCP tool results.
 *
 * <p>Besides the standard MCP {@code content} items a result may carry an additional {@code references} array which lists the loom domain entities the result
 * is about ({@code {"type":"asset","uuid":"…","label":"…"}}). External MCP clients simply ignore the extra field; the loom chat agent extracts it to render
 * entity chips for tool results.</p>
 */
public final class MCPToolResults {

	private MCPToolResults() {
	}

	/**
	 * Wrap a text string in MCP content format.
	 */
	public static JsonObject mcpTextResult(String text) {
		return new JsonObject()
			.put("content", new JsonArray()
				.add(new JsonObject()
					.put("type", "text")
					.put("text", text)));
	}

	/**
	 * Wrap a text string in MCP content format and attach the given domain entity references.
	 *
	 * @param text
	 *            Result text
	 * @param references
	 *            References built via {@link #reference(String, String, String)}. Empty or null arrays are omitted.
	 */
	public static JsonObject mcpResultWithReferences(String text, JsonArray references) {
		JsonObject result = mcpTextResult(text);
		if (references != null && !references.isEmpty()) {
			result.put("references", references);
		}
		return result;
	}

	/**
	 * Build a single domain entity reference.
	 *
	 * @param type
	 *            Entity type (e.g. asset, collection, task, comment, pipeline, annotation)
	 * @param uuid
	 *            Entity uuid
	 * @param label
	 *            Human readable label (e.g. filename, title, name)
	 */
	public static JsonObject reference(String type, String uuid, String label) {
		return new JsonObject()
			.put("type", type)
			.put("uuid", uuid)
			.put("label", label);
	}

}
