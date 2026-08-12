package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResultWithReferences;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: list_remixes
 *
 * <p>
 * Browse the named groups of assets that are versions of one another. Distinct from
 * {@code list_collections}: a collection groups assets by topic, a remix records that these
 * particular files <em>are</em> the same work in different forms.
 * </p>
 */
@Singleton
public class ListRemixesTool implements MCPTool {

	private final DaoCollection daos;

	@Inject
	public ListRemixesTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"list_remixes",
			"List remixes - named groups of assets that are versions of one another, such as an original video and the cuts made from it. "
				+ "Returns each remix's name, uuid and member count. Use get_remix to see what is in one. "
				+ "A remix is not a collection (which groups by topic) and not a duplicate group (which is the same bytes twice).",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("limit", "integer", "Maximum number of remixes to return (default: 25)", false)
			)),
			List.of("READ_REMIX")
		);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			int limit = arguments.getInteger("limit", 25);
			Page<Remix> page = daos.remixDao().loadPage(null, limit, null, null, null);

			JsonArray items = new JsonArray();
			JsonArray references = new JsonArray();
			for (Remix remix : page) {
				items.add(new JsonObject()
					.put("uuid", remix.getUuid().toString())
					.put("name", remix.getName())
					.put("description", remix.getDescription())
					.put("memberCount", daos.remixDao().countAssets(remix.getUuid())));
				references.add(reference("remix", remix.getUuid().toString(), remix.getName()));
			}

			return Future.succeededFuture(
				mcpResultWithReferences("Found " + items.size() + " remixes.\n" + items.encodePrettily(), references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
