package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResultWithReferences;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: list_collections
 *
 * <p>Browse and search asset collections. Collections provide hierarchical
 * grouping of assets for spaces or topics.</p>
 */
@Singleton
public class ListCollectionsTool implements MCPTool {

	private final DaoCollection daos;

	@Inject
	public ListCollectionsTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"list_collections",
			"List available asset collections. Collections group assets together for spaces or topics. Returns collection names and UUIDs.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("limit", "integer", "Maximum number of collections to return (default: 25)", false)
			)),
			List.of("READ_COLLECTION")
		);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			CollectionDao collectionDao = daos.collectionDao();
			int limit = arguments.getInteger("limit", 25);

			Page<Collection> page = collectionDao.loadPage(null, limit, null, null, null);

			JsonArray items = new JsonArray();
			JsonArray references = new JsonArray();
			for (Collection collection : page) {
				items.add(new JsonObject()
					.put("uuid", collection.getUuid().toString())
					.put("name", collection.getName()));
				references.add(reference("collection", collection.getUuid().toString(), collection.getName()));
			}

			return Future.succeededFuture(
				mcpResultWithReferences("Found " + items.size() + " collections.\n" + items.encodePrettily(), references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
