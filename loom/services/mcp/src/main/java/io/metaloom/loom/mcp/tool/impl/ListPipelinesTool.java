package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResultWithReferences;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: list_pipelines
 *
 * <p>Lists the processing pipelines with the metadata of their latest version. The node graph is deliberately not part of this result — a listing that
 * embedded every graph would blow up the context window; {@code get_pipeline} loads the graph of the one pipeline the user actually asked about.</p>
 */
@Singleton
public class ListPipelinesTool implements MCPTool {

	private final DaoCollection daos;

	@Inject
	public ListPipelinesTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"list_pipelines",
			"List the media processing pipelines. Returns name, description and uuid of each pipeline together with whether it is enabled. "
				+ "Use get_pipeline afterwards to load the node graph of a specific pipeline.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("query", "string", "Optional case-insensitive filter on the pipeline name or description", false),
				new MCPToolParam("limit", "integer", "Maximum number of pipelines to return (default: 25)", false))),
			List.of("READ_PIPELINE"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			int limit = arguments.getInteger("limit", 25);
			String query = arguments.getString("query");

			Page<Pipeline> page = daos.pipelineDao().loadPage(null, limit, null, null, null);
			Map<UUID, PipelineVersion> versions = latestVersions(page);

			JsonArray items = new JsonArray();
			JsonArray references = new JsonArray();
			for (Pipeline pipeline : page) {
				PipelineVersion version = versions.get(pipeline.getUuid());
				if (version == null || !matches(version, query)) {
					continue;
				}
				items.add(new JsonObject()
					.put("uuid", pipeline.getUuid().toString())
					.put("name", version.getName())
					.put("description", version.getDescription())
					.put("enabled", version.isEnabled())
					.put("versionNumber", version.getVersionNumber())
					.put("nodeCount", nodeCount(version)));
				references.add(reference("pipeline", pipeline.getUuid().toString(), version.getName()));
			}

			return Future.succeededFuture(
				mcpResultWithReferences("Found " + items.size() + " pipelines.\n" + items.encodePrettily(), references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	/**
	 * Resolve the latest version of every listed pipeline in one query rather than per row.
	 */
	private Map<UUID, PipelineVersion> latestVersions(Page<Pipeline> page) {
		Map<UUID, UUID> versionToPipeline = new HashMap<>();
		for (Pipeline pipeline : page) {
			if (pipeline.getLatestVersionUuid() != null) {
				versionToPipeline.put(pipeline.getLatestVersionUuid(), pipeline.getUuid());
			}
		}
		Map<UUID, PipelineVersion> byPipeline = new HashMap<>();
		if (versionToPipeline.isEmpty()) {
			return byPipeline;
		}
		for (PipelineVersion version : daos.pipelineVersionDao().loadByUuids(versionToPipeline.keySet())) {
			byPipeline.put(versionToPipeline.get(version.getUuid()), version);
		}
		return byPipeline;
	}

	private static boolean matches(PipelineVersion version, String query) {
		if (query == null || query.isBlank()) {
			return true;
		}
		String needle = query.toLowerCase();
		String name = version.getName() == null ? "" : version.getName().toLowerCase();
		String description = version.getDescription() == null ? "" : version.getDescription().toLowerCase();
		return name.contains(needle) || description.contains(needle);
	}

	private static int nodeCount(PipelineVersion version) {
		JsonObject definition = version.getDefinition();
		JsonArray nodes = definition == null ? null : definition.getJsonArray("nodes");
		return nodes == null ? 0 : nodes.size();
	}

}
