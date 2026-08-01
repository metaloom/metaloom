package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;
import static io.metaloom.loom.mcp.tool.MCPToolResults.visual;

import java.util.List;
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
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: get_pipeline
 *
 * <p>Loads one pipeline and returns its node graph both as text (so any MCP client can answer from it) and as a {@code pipeline-graph} visual which the loom
 * chat renders as a compact diagram inline in the conversation (see {@code MCPToolResults} and CHAT.md §6.1).</p>
 *
 * <p>The graph is the one of the pipeline's <b>latest version</b> — the version that would run today, which is what "the current pipeline" means to a user
 * asking the agent about it.</p>
 */
@Singleton
public class GetPipelineTool implements MCPTool {

	/**
	 * Visual type discriminator understood by the chat UI.
	 */
	public static final String VISUAL_TYPE = "pipeline-graph";

	/**
	 * A compact inline diagram stops being readable long before this, and the payload is persisted onto the chat transcript — so both are bounded.
	 */
	public static final int MAX_NODES = 40;

	public static final int MAX_EDGES = 80;

	/**
	 * How many pipelines are scanned when the argument is a name rather than a uuid.
	 */
	private static final int NAME_LOOKUP_PAGE_SIZE = 200;

	private final DaoCollection daos;

	private final NodeDescriptorRegistry nodeDescriptors;

	@Inject
	public GetPipelineTool(DaoCollection daos, NodeDescriptorRegistry nodeDescriptors) {
		this.daos = daos;
		this.nodeDescriptors = nodeDescriptors;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"get_pipeline",
			"Load a single processing pipeline including its node graph (nodes and the port-to-port connections between them). "
				+ "Accepts a pipeline UUID or a pipeline name. Use this to show or explain how a pipeline processes media.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("pipelineId", "string", "Pipeline UUID or pipeline name (case-insensitive)", true))),
			List.of("READ_PIPELINE"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String pipelineId = arguments.getString("pipelineId");
			if (pipelineId == null || pipelineId.isBlank()) {
				return Future.succeededFuture(mcpTextResult("ERROR: The pipelineId parameter is required."));
			}

			Pipeline pipeline = resolve(pipelineId.trim());
			if (pipeline == null) {
				return Future.succeededFuture(mcpTextResult("No pipeline found for: " + pipelineId));
			}
			PipelineVersion version = daos.pipelineVersionDao().loadLatestByPipeline(pipeline.getUuid());
			if (version == null) {
				return Future.succeededFuture(mcpTextResult("Pipeline " + pipeline.getUuid() + " has no version yet."));
			}

			JsonObject graph = graph(version);
			String uuid = pipeline.getUuid().toString();
			JsonObject payload = new JsonObject()
				.put("pipelineUuid", uuid)
				.put("name", version.getName())
				.put("description", version.getDescription())
				.put("enabled", version.isEnabled())
				.put("versionNumber", version.getVersionNumber())
				.mergeIn(graph);

			return Future.succeededFuture(mcpResult(
				describe(uuid, version, graph),
				new JsonArray().add(reference("pipeline", uuid, version.getName())),
				new JsonArray().add(visual(VISUAL_TYPE, uuid, version.getName(), payload))));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	/**
	 * Resolve the argument as a uuid first and fall back to a case-insensitive name match, because a user asks for "the transcription pipeline", not for a
	 * uuid, and the model passes that phrasing straight through.
	 */
	private Pipeline resolve(String pipelineId) {
		try {
			Pipeline byUuid = daos.pipelineDao().loadWithLatestVersion(UUID.fromString(pipelineId));
			if (byUuid != null) {
				return byUuid;
			}
		} catch (IllegalArgumentException e) {
			// Not a uuid — fall through to the name lookup
		}

		Page<Pipeline> page = daos.pipelineDao().loadPage(null, NAME_LOOKUP_PAGE_SIZE, null, null, null);
		Pipeline partialMatch = null;
		for (Pipeline pipeline : page) {
			PipelineVersion version = daos.pipelineVersionDao().loadLatestByPipeline(pipeline.getUuid());
			String name = version == null || version.getName() == null ? null : version.getName();
			if (name == null) {
				continue;
			}
			if (name.equalsIgnoreCase(pipelineId)) {
				return pipeline;
			}
			if (partialMatch == null && name.toLowerCase().contains(pipelineId.toLowerCase())) {
				partialMatch = pipeline;
			}
		}
		return partialMatch;
	}

	/**
	 * Project the stored definition onto the render payload: node identity, kind, label and category, plus the port-to-port edges. Editor-only fields
	 * ({@code x}/{@code y}) and node options are dropped — the compact diagram does not draw them and they would only cost context.
	 */
	private JsonObject graph(PipelineVersion version) {
		JsonObject definition = version.getDefinition() == null ? new JsonObject() : version.getDefinition();
		JsonArray definitionNodes = definition.getJsonArray("nodes", new JsonArray());
		JsonArray definitionEdges = definition.getJsonArray("edges", new JsonArray());

		JsonArray nodes = new JsonArray();
		for (int i = 0; i < definitionNodes.size() && nodes.size() < MAX_NODES; i++) {
			JsonObject node = definitionNodes.getJsonObject(i);
			if (node == null) {
				continue;
			}
			String kind = node.getString("type");
			NodeDescriptor descriptor = kind == null || nodeDescriptors == null ? null : nodeDescriptors.get(kind);
			String label = node.getString("label", node.getString("name"));
			if (label == null) {
				label = descriptor != null ? descriptor.getName() : kind;
			}
			nodes.add(new JsonObject()
				.put("id", node.getString("id"))
				.put("kind", kind)
				.put("label", label)
				.put("category", descriptor != null && descriptor.getCategory() != null ? descriptor.getCategory().name() : "ANALYSIS"));
		}

		JsonArray edges = new JsonArray();
		for (int i = 0; i < definitionEdges.size() && edges.size() < MAX_EDGES; i++) {
			JsonObject edge = definitionEdges.getJsonObject(i);
			if (edge == null) {
				continue;
			}
			JsonObject rendered = new JsonObject()
				.put("source", edge.getString("source"))
				.put("sourcePort", edge.getString("sourcePort"))
				.put("target", edge.getString("target"))
				.put("targetPort", edge.getString("targetPort"));
			String branch = edge.getString("branch");
			if (branch != null) {
				rendered.put("branch", branch);
			}
			edges.add(rendered);
		}

		JsonObject graph = new JsonObject()
			.put("nodes", nodes)
			.put("edges", edges);
		if (definitionNodes.size() > nodes.size() || definitionEdges.size() > edges.size()) {
			graph.put("truncated", true);
		}
		return graph;
	}

	/**
	 * The text rendering of the graph. A client that ignores the visual — and the model itself, which only ever sees this text — must be able to describe the
	 * pipeline from it alone.
	 */
	private static String describe(String uuid, PipelineVersion version, JsonObject graph) {
		JsonArray nodes = graph.getJsonArray("nodes");
		JsonArray edges = graph.getJsonArray("edges");

		StringBuilder sb = new StringBuilder();
		sb.append("Pipeline: ").append(version.getName()).append("\n");
		sb.append("uuid: ").append(uuid).append("\n");
		if (version.getDescription() != null && !version.getDescription().isBlank()) {
			sb.append("description: ").append(version.getDescription()).append("\n");
		}
		sb.append("version: ").append(version.getVersionNumber())
			.append(" · ").append(version.isEnabled() ? "enabled" : "disabled")
			.append(version.isDryRun() ? " · dry-run" : "")
			.append(" · priority ").append(version.getPriority()).append("\n");

		sb.append("\nNodes (").append(nodes.size()).append("):\n");
		for (int i = 0; i < nodes.size(); i++) {
			JsonObject node = nodes.getJsonObject(i);
			sb.append("- ").append(node.getString("id")).append(" ").append(node.getString("label"))
				.append(" [").append(node.getString("kind")).append(", ").append(node.getString("category")).append("]\n");
		}

		sb.append("\nConnections (").append(edges.size()).append("):\n");
		for (int i = 0; i < edges.size(); i++) {
			JsonObject edge = edges.getJsonObject(i);
			sb.append("- ").append(edge.getString("source")).append(".").append(edge.getString("sourcePort"))
				.append(" -> ").append(edge.getString("target")).append(".").append(edge.getString("targetPort"));
			if (edge.getString("branch") != null) {
				sb.append(" (branch ").append(edge.getString("branch")).append(")");
			}
			sb.append("\n");
		}
		if (graph.getBoolean("truncated", false)) {
			sb.append("\n(The graph was truncated — it exceeds ").append(MAX_NODES).append(" nodes or ").append(MAX_EDGES).append(" connections.)\n");
		}
		return sb.toString();
	}

}
