package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.mcp.tool.MCPToolResults;
import io.metaloom.loom.mcp.tool.impl.search.FindAssetsQuery;
import io.metaloom.loom.mcp.tool.impl.search.SearchVocabulary;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: find_assets
 *
 * <p>
 * The structured half of natural-language search. {@code search_assets} takes a term and little else; this takes the whole question - who made it, when,
 * in which project, of what kind - as a bounded object, and answers what it actually did.
 * </p>
 *
 * <p>
 * <b>What makes it work is not the schema, it is the resolution.</b> Every scope field accepts the <i>name</i> a person used. "pete" and "Project XYZ"
 * are turned into uuids by {@link SearchVocabulary} before the query is built, because the query layer takes uuids and a model asked for one will invent
 * it. The same applies to dates: "yesterday" is resolved against the server clock rather than asked of a model that has none.
 * </p>
 *
 * <p>
 * <b>Identity-scoped, unlike {@code search_assets}.</b> This tool declares {@code requiresIdentity}, so it is dispatched in-process with a resolved
 * {@link MCPCallerContext} and has no EventBus address at all. It sets {@code SearchRequest.userUuid} from that context, never from its arguments. The
 * types it will search - {@code asset} and {@code transcript} - both require {@code READ_ASSET}, which the descriptor demands, so the per-type narrowing
 * {@code SearchEndpointService} performs over REST is satisfied here by construction rather than by a second copy of that logic.
 * </p>
 *
 * <p>
 * <b>Three outcomes stay distinguishable</b>: hits, nothing matched, and the search could not run. A refusal is returned as readable text rather than a
 * failed future, so the model can correct itself and try again within the same turn; only genuinely unexpected faults fail.
 * </p>
 */
@Singleton
public class FindAssetsTool implements MCPTool {

	public static final String NAME = "find_assets";

	/** Enough to answer "which ones", short enough that the result does not crowd the context window. */
	private static final int MAX_RENDERED = 50;

	private final SearchProvider provider;

	private final SearchVocabulary vocabulary;

	private final SearchOptions options;

	@Inject
	public FindAssetsTool(SearchProvider provider, SearchVocabulary vocabulary, LoomOptions loomOptions) {
		this.provider = provider;
		this.vocabulary = vocabulary;
		this.options = loomOptions.getSearch();
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Find assets by any combination of what they contain, who created them, when, and where they live. "
				+ "Prefer this over search_assets whenever the question has more to it than a search term. "
				+ "Pass names, not uuids - 'pete', 'Project XYZ', 'yesterday' are resolved server-side, and an unknown or "
				+ "ambiguous name is reported rather than ignored. 'text' is optional: filters alone are a valid query, so "
				+ "'everything Pete uploaded yesterday' needs no search term. The tool reports which filters it applied.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("text", "string",
					"Words to match. Searches filenames, paths, transcripts, document text, captions, detection labels and tag names. "
						+ "Supports \"quoted phrases\", or, and -negation. Omit it to filter only.", false),
				new MCPToolParam("creator", "string", "Who created/uploaded the asset - a username, real name, email or uuid", false),
				new MCPToolParam("collection", "string", "Restrict to a collection, by name or uuid", false),
				new MCPToolParam("library", "string", "Restrict to a library, by name or uuid", false),
				new MCPToolParam("space", "string", "Restrict to a space (project), by name or uuid", false),
				new MCPToolParam("tags", "array", "Restrict to assets carrying any of these tag names", false),
				new MCPToolParam("when", "string",
					"A single period the asset was created in: today, yesterday, 'today or yesterday', this week, last week, "
						+ "this month, last month, 'last 7 days', '3 days ago', or a date like 2026-08-18", false),
				new MCPToolParam("createdFrom", "string", "Lower bound of the creation date, same vocabulary as 'when'", false),
				new MCPToolParam("createdTo", "string", "Upper bound of the creation date, same vocabulary as 'when'", false),
				new MCPToolParam("mimeType", "string", "MIME type prefix, e.g. image/ or video/mp4", false),
				new MCPToolParam("types", "array",
					"What to return: asset (default) or transcript. An asset's own document already contains its transcripts, "
						+ "captions, OCR text and tags, so use transcript only when you need the timecode of a passage.", false),
				new MCPToolParam("sort", "string", "Result order", false,
					List.of("RELEVANCE", "NEWEST", "OLDEST", "NAME", "SIZE")),
				new MCPToolParam("mode", "string", "Ranking. SEMANTIC and HYBRID need a 'text' and may be unavailable.", false,
					List.of("LEXICAL", "SEMANTIC", "HYBRID")),
				new MCPToolParam("highlight", "boolean", "Return the matching snippet of each hit (default false)", false),
				new MCPToolParam("timezone", "string", "IANA zone the date words are read in, e.g. Europe/Vienna. Defaults to the server zone.", false),
				new MCPToolParam("limit", "integer", "Maximum number of results (default 25)", false),
				new MCPToolParam("offset", "integer", "Result offset for paging (default 0)", false))),
			List.of("READ_SEARCH", "READ_ASSET"),
			true);
	}

	/** Unreachable by construction: an identity-scoped tool has no EventBus address. */
	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(NAME + " requires an authenticated caller and cannot be dispatched without one.");
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		try {
			JsonObject unavailable = SearchToolSupport.unavailable(provider);
			if (unavailable != null) {
				return Future.succeededFuture(unavailable);
			}

			FindAssetsQuery.Plan plan = FindAssetsQuery.build(arguments, vocabulary, options, Instant.now());
			if (plan.isError()) {
				return Future.succeededFuture(mcpTextResult("Could not run the search: " + plan.error()));
			}

			SearchRequest request = plan.request();
			// Identity comes from the resolved context and nowhere else. It is inert in the provider today
			// (row-level ACL is still a global gate) but populating it here means switching that on later
			// needs no change to this tool.
			request.setUserUuid(ctx.userUuid());

			SearchResult result;
			try {
				result = provider.search(request);
			} catch (RuntimeException e) {
				return Future.succeededFuture(SearchToolSupport.failed(e));
			}
			return Future.succeededFuture(render(result, plan));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	private JsonObject render(SearchResult result, FindAssetsQuery.Plan plan) {
		// Keyed by asset so a transcript row and its asset row collapse into one entry. Two rows for one
		// file would be read as two files, and the count is the part of the answer a user acts on.
		Map<UUID, JsonObject> items = new LinkedHashMap<>();
		JsonArray references = new JsonArray();

		for (SearchHit hit : result.getHits()) {
			UUID assetUuid = hit.getAssetUuid() != null ? hit.getAssetUuid() : hit.getUuid();
			if (assetUuid == null) {
				continue;
			}
			JsonObject existing = items.get(assetUuid);
			if (existing != null) {
				// The collapsed row may carry what the kept one does not: a transcript hit knows where in
				// the timeline the words were said, and dropping that loses the only reason to ask for
				// transcripts at all.
				merge(existing, hit);
				continue;
			}
			if (items.size() >= MAX_RENDERED) {
				continue;
			}
			JsonObject item = new JsonObject()
				.put("uuid", assetUuid.toString())
				.put("title", hit.getTitle())
				.put("mimeType", hit.getMimeType())
				.put("size", hit.getSize())
				.put("created", hit.getSortDate() == null ? null : hit.getSortDate().toString())
				.put("score", hit.getScore());
			merge(item, hit);
			items.put(assetUuid, item);
			references.add(MCPToolResults.reference("asset", assetUuid.toString(), hit.getTitle()));
		}

		JsonArray rendered = new JsonArray(List.copyOf(items.values()));
		String criteria = plan.applied().isEmpty() ? "no filters" : String.join(", ", plan.applied());
		// "More" is about the corpus, not about this page: rows already collapsed by dedup are not more
		// results, and reporting them as such invites a pointless second page.
		boolean more = result.getTotalHits() > (long) result.getHits().size() + plan.request().getOffset();

		StringBuilder text = new StringBuilder();
		if (rendered.isEmpty()) {
			// Restating the criteria is what stops the model reporting an empty catalogue when what was
			// actually empty is one narrow slice of it.
			text.append("No assets matched (").append(criteria).append(").");
		} else {
			if (more) {
				text.append("Showing ").append(rendered.size()).append(" of ").append(result.getTotalHits())
					.append(result.isTotalExact() ? "" : " (approximate)").append(" matches");
			} else {
				text.append("Found ").append(rendered.size()).append(rendered.size() == 1 ? " asset" : " assets");
			}
			text.append(" (").append(criteria).append(").\n").append(rendered.encodePrettily());
		}
		for (String warning : result.getWarnings()) {
			text.append("\nNote: ").append(warning);
		}
		return MCPToolResults.mcpResultWithReferences(text.toString(), references);
	}

	/**
	 * Fold what a hit knows into the entry for its asset, without overwriting anything already there. The first hit for an asset is the highest ranked
	 * one, so its title and score win; a later, lower-ranked row only ever contributes a field the winner left empty.
	 */
	private void merge(JsonObject item, SearchHit hit) {
		if (hit.getTimeFromMs() != null && item.getValue("timeFromMs") == null) {
			item.put("timeFromMs", hit.getTimeFromMs());
		}
		if (item.getValue("snippet") == null && !hit.getHighlights().isEmpty()) {
			String snippet = SearchToolSupport.plainSnippet(hit.getHighlights().get(0));
			if (snippet != null) {
				item.put("snippet", snippet);
			}
		}
	}

}
