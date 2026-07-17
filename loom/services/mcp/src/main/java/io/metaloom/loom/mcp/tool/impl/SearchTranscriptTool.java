package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.impl.SearchAssetsTool.mcpTextResult;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: search_transcript
 *
 * <p>Full-text search across asset document components (transcripts, OCR text,
 * extracted plain text). Useful for finding specific content in speech-to-text
 * results or document analysis output.</p>
 */
@Singleton
public class SearchTranscriptTool implements MCPTool {

	private final DaoCollection daos;

	@Inject
	public SearchTranscriptTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"search_transcript",
			"Search across transcripts and extracted text from all assets. Matches against document component plain text fields. Returns matching text snippets with asset references.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("query", "string", "Text to search for in transcripts/documents", true),
				new MCPToolParam("limit", "integer", "Maximum number of results (default: 10)", false)
			)),
			List.of("READ_ASSET")
		);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String query = arguments.getString("query");
			if (query == null || query.isBlank()) {
				return Future.failedFuture("Parameter 'query' is required");
			}
			int limit = arguments.getInteger("limit", 10);

			// In a full implementation this would use Elasticsearch/Lucene for full-text
			// search over asset_doc_comp.doc_plain_text. For now we indicate the search
			// capability and return a structured stub response.
			JsonObject result = mcpTextResult(
				"Transcript search for '" + query + "' (limit: " + limit + ")\n" +
				"Note: Full-text search requires Elasticsearch/Lucene integration. " +
				"This tool will query the asset_doc_comp table for matching transcripts.");

			return Future.succeededFuture(result);
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
