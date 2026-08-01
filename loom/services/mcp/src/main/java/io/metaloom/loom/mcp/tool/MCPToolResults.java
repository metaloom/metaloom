package io.metaloom.loom.mcp.tool;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Helpers for building MCP tool results.
 *
 * <p>Besides the standard MCP {@code content} items a result may carry two additional arrays:</p>
 * <ul>
 * <li>{@code references} — the loom domain entities the result is about ({@code {"type":"asset","uuid":"…","label":"…"}}), rendered as entity chips.</li>
 * <li>{@code visuals} — renderable payloads the chat UI draws inline instead of (only) printing text, e.g. a pipeline graph
 * ({@code {"type":"pipeline-graph","uuid":"…","label":"…","payload":{…}}}).</li>
 * </ul>
 *
 * <p>Both are extras on top of the MCP content format: external MCP clients simply ignore them and read the text, while the loom chat agent extracts them
 * (see {@code ReferenceExtractor} / {@code VisualExtractor}) and relays them to the UI.</p>
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
	 * Wrap a text string in MCP content format and attach domain entity references plus renderable visuals.
	 *
	 * @param text
	 *            Result text. Always carries the full information — a client that cannot render the visual must still be able to answer from the text.
	 * @param references
	 *            References built via {@link #reference(String, String, String)}. Empty or null arrays are omitted.
	 * @param visuals
	 *            Visuals built via {@link #visual(String, String, String, JsonObject)}. Empty or null arrays are omitted.
	 */
	public static JsonObject mcpResult(String text, JsonArray references, JsonArray visuals) {
		JsonObject result = mcpResultWithReferences(text, references);
		if (visuals != null && !visuals.isEmpty()) {
			result.put("visuals", visuals);
		}
		return result;
	}

	/**
	 * Build a single renderable visual.
	 *
	 * @param type
	 *            Visual kind understood by the UI (currently only {@code pipeline-graph})
	 * @param uuid
	 *            Uuid of the entity the visual depicts, so the UI can link into the matching view
	 * @param label
	 *            Human readable label (e.g. the pipeline name)
	 * @param payload
	 *            The type-specific render payload
	 */
	public static JsonObject visual(String type, String uuid, String label, JsonObject payload) {
		return new JsonObject()
			.put("type", type)
			.put("uuid", uuid)
			.put("label", label)
			.put("payload", payload);
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
