package io.metaloom.loom.agent.chat.ref;

import java.util.LinkedHashSet;
import java.util.Set;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Extracts renderable visuals ({@code {"type","uuid","label","payload"}}) from tool results so the chat can draw them inline — currently the
 * {@code pipeline-graph} of {@code get_pipeline} (see {@code MCPToolResults} and CHAT.md §6.1).
 *
 * <p>A visual is an <em>enhancement</em> of the tool result, never its substance: the model only ever sees the text content, so dropping a visual here can
 * cost a diagram but never an answer. That is why the caps below simply discard instead of failing the tool call.</p>
 */
public class VisualExtractor {

	/**
	 * Maximum number of visuals collected per assistant message. Each one is a card in the transcript; a run that produced more has stopped being a chat
	 * answer.
	 */
	public static final int MAX_VISUALS = 4;

	/**
	 * Encoded size limit of a single visual. The visuals are persisted onto the chat row together with the transcript, so an unbounded payload would grow the
	 * row on every exchange.
	 */
	public static final int MAX_VISUAL_BYTES = 32 * 1024;

	private final JsonArray visuals = new JsonArray();

	private final Set<String> seen = new LinkedHashSet<>();

	/**
	 * Extract the visuals of a single tool result.
	 *
	 * @param toolResult
	 *            The raw tool result
	 * @return The visuals of this result (already deduplicated against previously collected ones)
	 */
	public JsonArray extract(JsonObject toolResult) {
		JsonArray fresh = new JsonArray();
		if (toolResult == null) {
			return fresh;
		}
		JsonArray candidates = toolResult.getJsonArray("visuals");
		if (candidates == null) {
			return fresh;
		}
		for (int i = 0; i < candidates.size(); i++) {
			JsonObject visual = candidates.getJsonObject(i);
			if (visual == null) {
				continue;
			}
			String type = visual.getString("type");
			JsonObject payload = visual.getJsonObject("payload");
			if (type == null || payload == null) {
				continue;
			}
			String key = type + ":" + visual.getString("uuid", String.valueOf(i));
			if (visuals.size() >= MAX_VISUALS || !seen.add(key)) {
				continue;
			}
			if (visual.encode().length() > MAX_VISUAL_BYTES) {
				continue;
			}
			fresh.add(visual);
			visuals.add(visual);
		}
		return fresh;
	}

	/**
	 * All visuals collected so far (deduplicated, capped at {@link #MAX_VISUALS}).
	 */
	public JsonArray visuals() {
		return visuals;
	}

}
