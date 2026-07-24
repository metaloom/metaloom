package io.metaloom.loom.agent.chat.ref;

import java.util.LinkedHashSet;
import java.util.Set;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Extracts domain entity references ({@code {"type","uuid","label"}}) from tool results so the UI can render entity chips. The MCP tools attach a structured
 * {@code references} array to their results (see {@code MCPToolResults}); results without one yield no references.
 */
public class ReferenceExtractor {

	/**
	 * Maximum number of references collected per assistant message.
	 */
	public static final int MAX_REFERENCES = 20;

	private final JsonArray references = new JsonArray();

	private final Set<String> seen = new LinkedHashSet<>();

	/**
	 * Extract the references of a single tool result.
	 *
	 * @param toolResult
	 *            The raw tool result
	 * @return The references of this result (already deduplicated against previously collected ones)
	 */
	public JsonArray extract(JsonObject toolResult) {
		JsonArray fresh = new JsonArray();
		if (toolResult == null) {
			return fresh;
		}
		JsonArray refs = toolResult.getJsonArray("references");
		if (refs == null) {
			return fresh;
		}
		for (int i = 0; i < refs.size(); i++) {
			JsonObject ref = refs.getJsonObject(i);
			String type = ref.getString("type");
			String uuid = ref.getString("uuid");
			if (type == null || uuid == null) {
				continue;
			}
			String key = type + ":" + uuid;
			if (references.size() >= MAX_REFERENCES || !seen.add(key)) {
				continue;
			}
			fresh.add(ref);
			references.add(ref);
		}
		return fresh;
	}

	/**
	 * All references collected so far (deduplicated, capped at {@link #MAX_REFERENCES}).
	 */
	public JsonArray references() {
		return references;
	}

}
