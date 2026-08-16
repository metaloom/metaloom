package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;
import java.util.Set;
import java.util.UUID;

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
 * MCP tool: search_assets
 *
 * <p>
 * Ranked search over assets, served by the {@code SearchProvider} SPI — the same backend the {@code /api/v1/search/*} routes use, so the model and the
 * UI see one ranking and one corpus. The term reaches filenames, paths, transcripts, OCR and Tika text, captions, detection labels and tag names,
 * because they are all folded into that asset's {@code search_document}.
 * </p>
 *
 * <p>
 * ⚠️ <b>No authorization narrowing happens here.</b> {@code MCPTool.execute(JsonObject)} carries no caller identity, so the per-entity-type narrowing
 * {@code SearchEndpointService} applies over REST cannot apply. {@code descriptor().permissions()} gates the dispatch and nothing more. That matches
 * the rest of the MCP server, which calls DAOs directly.
 * </p>
 */
@Singleton
public class SearchAssetsTool implements MCPTool {

	private final SearchProvider provider;

	@Inject
	public SearchAssetsTool(SearchProvider provider) {
		this.provider = provider;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"search_assets",
			"Search for assets by filename, path, transcript, extracted document text, caption, detection label or tag. Returns a ranked, paginated list of matching assets.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("query", "string", "Search term. Supports \"quoted phrases\", or, and -negation", true),
				new MCPToolParam("mimeType", "string", "Filter by MIME type prefix (e.g. 'image/jpeg' or 'video/')", false),
				new MCPToolParam("library", "string", "Restrict to a library, by uuid", false),
				new MCPToolParam("tag", "string", "Restrict to assets carrying this tag name", false),
				new MCPToolParam("limit", "integer", "Maximum number of results to return (default: 25)", false),
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
				.setTypes(Set.of(SearchEntityType.ASSET))
				.setMimeTypePrefix(SearchToolSupport.mimeTypePrefix(arguments.getString("mimeType")))
				.setLimit(arguments.getInteger("limit", 25))
				.setOffset(arguments.getInteger("offset", 0));

			String tag = arguments.getString("tag");
			if (tag != null && !tag.isBlank()) {
				request.setTags(List.of(tag.trim()));
			}
			String library = arguments.getString("library");
			if (library != null && !library.isBlank()) {
				try {
					request.setLibraryUuid(UUID.fromString(library.trim()));
				} catch (IllegalArgumentException e) {
					// Answer rather than throw: the model should be able to correct itself.
					return Future.succeededFuture(mcpTextResult("Not a uuid: " + library));
				}
			}

			SearchResult result;
			try {
				result = provider.search(request);
			} catch (RuntimeException e) {
				return Future.succeededFuture(SearchToolSupport.failed(e));
			}

			JsonArray items = new JsonArray();
			JsonArray references = new JsonArray();
			for (SearchHit hit : result.getHits()) {
				// For an asset hit the two uuids are the same; reading assetUuid keeps this correct if
				// the type filter is ever widened.
				UUID assetUuid = hit.getAssetUuid() != null ? hit.getAssetUuid() : hit.getUuid();
				items.add(new JsonObject()
					.put("uuid", assetUuid == null ? null : assetUuid.toString())
					.put("title", hit.getTitle())
					.put("mimeType", hit.getMimeType())
					.put("size", hit.getSize())
					.put("score", hit.getScore()));
				if (assetUuid != null) {
					references.add(MCPToolResults.reference("asset", assetUuid.toString(), hit.getTitle()));
				}
			}

			String text = items.isEmpty()
				? "No assets matched '" + query + "'."
				: "Found " + items.size() + " of " + result.getTotalHits() + " matching assets for '" + query + "'.\n" + items.encodePrettily();
			return Future.succeededFuture(MCPToolResults.mcpResultWithReferences(text, references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
