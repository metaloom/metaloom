package io.metaloom.loom.mcp.tool.impl;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.mcp.tool.MCPToolResults;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The {@code asset-results} visual: the result set of a search, in a shape the chat can both draw in the transcript and mirror into its workspace
 * panel.
 *
 * <p>
 * <b>Why this is not just the {@code references} array.</b> The references of a search result already name the assets, and the chat already renders
 * them as chips. What a chip cannot carry is the things that make a result set a result set: the order the ranking put the rows in, how many matched
 * in total against how many are shown, which passage matched and at what offset, and what was searched for in the first place. The workspace panel
 * beside the conversation is a browser over the catalog, and to show "the assets we are talking about" it needs exactly those - otherwise it can only
 * show an unordered bag of files with no heading.
 * </p>
 *
 * <p>
 * <b>The model never sees this.</b> Like every visual it is stripped before the result reaches the model, so each producing tool still renders the
 * same information as text. Dropping the visual costs the thumbnails and the panel sync, never the answer.
 * </p>
 */
final class AssetResultsVisual {

	/** The visual type the chat renders as a result strip, and mirrors into the workspace panel. */
	static final String VISUAL_TYPE = "asset-results";

	/**
	 * Rows carried to the client.
	 *
	 * <p>
	 * Lower than the 50 a search tool will render as text, and deliberately: a visual is capped at 32 KB by {@code VisualExtractor} and silently
	 * discarded when it exceeds that, so a payload sized to the text limit would be the one that vanishes on exactly the searches worth looking at.
	 * Twenty-four rows is more than the panel shows without scrolling and about 4 KB.
	 * </p>
	 */
	static final int MAX_ITEMS = 24;

	/** A snippet is a line under a filename, not the passage. */
	static final int MAX_SNIPPET_CHARS = 160;

	private final JsonArray items = new JsonArray();

	private final Set<String> seen = new LinkedHashSet<>();

	/**
	 * Add one row, in ranking order. A second row for an asset already added is dropped - the panel lists files, and one file twice reads as two files.
	 *
	 * @param assetUuid
	 *            The asset the row is about. Null rows are skipped: a row the viewer cannot open is not a result.
	 * @param timeFromMs
	 *            Offset of the matching passage, when the hit knows one. It is what lets a click open the player where the words were said.
	 */
	void add(UUID assetUuid, String title, String mimeType, Long size, Double score, Long timeFromMs, String snippet) {
		if (assetUuid == null || items.size() >= MAX_ITEMS || !seen.add(assetUuid.toString())) {
			return;
		}
		JsonObject item = new JsonObject()
			.put("uuid", assetUuid.toString())
			.put("title", title)
			.put("mimeType", mimeType);
		if (size != null) {
			item.put("size", size);
		}
		if (score != null) {
			item.put("score", score);
		}
		if (timeFromMs != null) {
			item.put("timeFromMs", timeFromMs);
		}
		if (snippet != null && !snippet.isBlank()) {
			String trimmed = snippet.trim();
			item.put("snippet", trimmed.length() <= MAX_SNIPPET_CHARS ? trimmed : trimmed.substring(0, MAX_SNIPPET_CHARS).trim() + "…");
		}
		items.add(item);
	}

	boolean isEmpty() {
		return items.isEmpty();
	}

	/**
	 * The {@code visuals} array to hang on the tool result, or an empty array when nothing matched - an empty result set is reported in the text and
	 * must not blank the panel, which may be showing the previous search the user is still working with.
	 *
	 * @param label
	 *            What was searched for, as the user would say it. Becomes the heading of the strip and of the panel.
	 * @param criteria
	 *            The filters that were applied, for the subheading. Also part of the identity below.
	 * @param total
	 *            Matches in the corpus, which is usually more than the rows carried here.
	 * @param totalExact
	 *            False when the provider only estimated {@code total}, so the card can say "about".
	 */
	JsonArray build(String label, String criteria, long total, boolean totalExact) {
		if (items.isEmpty()) {
			return new JsonArray();
		}
		JsonObject payload = new JsonObject()
			.put("query", label)
			.put("criteria", criteria)
			.put("total", total)
			.put("totalExact", totalExact)
			.put("items", items);
		// Identity is the search, not the run: two identical searches inside one answer are one result set, and
		// VisualExtractor dedupes on (type, uuid) - which is what keeps a model that re-ran its own query from
		// spending the four-visual budget on four copies of one strip.
		String identity = VISUAL_TYPE + "|" + label + "|" + criteria;
		UUID uuid = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
		return new JsonArray().add(MCPToolResults.visual(VISUAL_TYPE, uuid.toString(), label, payload));
	}

}
