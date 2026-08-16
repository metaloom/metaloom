package io.metaloom.loom.mcp.tool.impl;

import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.mcp.tool.MCPToolResults;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: search_transcript
 *
 * <p>
 * Spoken-word search, served by the {@code SearchProvider} SPI with {@code types=[TRANSCRIPT]} and highlighting on. A transcript is indexed as a
 * document of its own precisely so a hit can carry its offset into the media, so every result here comes back with an {@code assetUuid} and a
 * {@code timeFromMs} the caller can deep-link with.
 * </p>
 *
 * <p>
 * Use {@code search_assets} to find the asset a phrase occurs in — the transcript text is folded into the asset's document too. Use this tool to find
 * <i>where</i> in that asset it was said.
 * </p>
 *
 * <p>
 * ⚠️ <b>No authorization narrowing happens here</b>, for the same reason as in {@link SearchAssetsTool}: {@code MCPTool.execute(JsonObject)} carries no
 * caller identity.
 * </p>
 */
@Singleton
public class SearchTranscriptTool implements MCPTool {

	private final SearchProvider provider;

	@Inject
	public SearchTranscriptTool(SearchProvider provider) {
		this.provider = provider;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"search_transcript",
			"Search spoken content across asset transcripts. Returns matching snippets with the asset they belong to and the offset into the media, so a result can be played from the moment it was said.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("query", "string", "Text to search for in transcripts. Supports \"quoted phrases\", or, and -negation", true),
				new MCPToolParam("limit", "integer", "Maximum number of results (default: 10)", false),
				new MCPToolParam("offset", "integer", "Result offset for paging (default: 0)", false))),
			List.of("READ_ASSET"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String query = arguments.getString("query");
			if (query == null || query.isBlank()) {
				return Future.failedFuture("Parameter 'query' is required");
			}

			JsonObject unavailable = SearchToolSupport.unavailable(provider);
			if (unavailable != null) {
				return Future.succeededFuture(unavailable);
			}

			SearchRequest request = new SearchRequest()
				.setQuery(query)
				.setTypes(Set.of(SearchEntityType.TRANSCRIPT))
				.setHighlight(true)
				.setLimit(arguments.getInteger("limit", 10))
				.setOffset(arguments.getInteger("offset", 0));

			SearchResult result;
			try {
				result = provider.search(request);
			} catch (RuntimeException e) {
				return Future.succeededFuture(SearchToolSupport.failed(e));
			}

			JsonArray items = new JsonArray();
			JsonArray references = new JsonArray();
			for (SearchHit hit : result.getHits()) {
				items.add(new JsonObject()
					.put("assetUuid", hit.getAssetUuid() == null ? null : hit.getAssetUuid().toString())
					.put("title", hit.getTitle())
					.put("timeFromMs", hit.getTimeFromMs())
					.put("snippet", snippet(hit))
					.put("score", hit.getScore()));
				if (hit.getAssetUuid() != null) {
					references.add(MCPToolResults.reference("asset", hit.getAssetUuid().toString(), hit.getTitle()));
				}
			}

			String text = items.isEmpty()
				? "No transcript matched '" + query + "'."
				: "Found " + items.size() + " of " + result.getTotalHits() + " transcript matches for '" + query + "'.\n" + items.encodePrettily();
			return Future.succeededFuture(MCPToolResults.mcpResultWithReferences(text, references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	/**
	 * The matched passage. Highlighting is a best-effort second pass in the provider and its failures are swallowed there, so a hit can legitimately
	 * arrive without one — fall back to the transcript's own subtitle rather than emitting a snippet-less row.
	 */
	private String snippet(SearchHit hit) {
		for (String highlight : hit.getHighlights()) {
			String snippet = SearchToolSupport.plainSnippet(highlight);
			if (snippet != null) {
				return snippet;
			}
		}
		return hit.getSubtitle();
	}

}
