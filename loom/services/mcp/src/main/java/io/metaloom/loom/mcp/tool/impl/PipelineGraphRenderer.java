package io.metaloom.loom.mcp.tool.impl;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.mcp.tool.MCPToolResults;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService.PipelineWithVersion;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Turns a stored pipeline into the three renderings the MCP tools hand back: text, a {@code pipeline-graph} visual payload, and the uuid-or-name
 * lookup that finds the pipeline in the first place.
 *
 * <p>
 * Extracted from {@code GetPipelineTool} when {@code create_pipeline} and {@code update_pipeline} arrived: a tool that has just authored a graph should
 * show it exactly as {@code get_pipeline} would, and a second copy of the projection is how the chat ends up drawing two different diagrams of the
 * same pipeline.
 * </p>
 */
@Singleton
public class PipelineGraphRenderer {

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
	public PipelineGraphRenderer(DaoCollection daos, NodeDescriptorRegistry nodeDescriptors) {
		this.daos = daos;
		this.nodeDescriptors = nodeDescriptors;
	}

	/**
	 * Resolve the argument as a uuid first and fall back to a case-insensitive name match, because a user asks for "the transcription pipeline", not
	 * for a uuid, and the model passes that phrasing straight through.
	 *
	 * @param pipelineId
	 *            a uuid or a pipeline name
	 * @return the pipeline, or {@code null} when nothing matches
	 */
	public Pipeline resolve(String pipelineId) {
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
	public JsonObject graph(PipelineVersion version) {
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
	 * The {@code pipeline-graph} visual payload: the graph plus the header fields the card shows.
	 */
	public JsonObject payload(UUID pipelineUuid, PipelineVersion version, JsonObject graph) {
		return new JsonObject()
			.put("pipelineUuid", pipelineUuid.toString())
			.put("name", version.getName())
			.put("description", version.getDescription())
			.put("enabled", version.isEnabled())
			.put("versionNumber", version.getVersionNumber())
			.mergeIn(graph);
	}

	/**
	 * The full result of an authoring write: the text a model reads, one pipeline chip, and the same {@code pipeline-graph} card {@code get_pipeline}
	 * produces. Showing the stored graph back is how an author checks that what was written is the graph they meant.
	 *
	 * @param result
	 *            the pipeline and the version that was just written
	 * @param headline
	 *            the first line, e.g. {@code "Created pipeline"}
	 */
	public JsonObject describeWrite(PipelineWithVersion result, String headline) {
		String uuid = result.pipeline().getUuid().toString();
		JsonObject graph = graph(result.version());
		String text = headline + " (version " + result.version().getVersionNumber() + ").\n\n"
			+ describe(uuid, result.version(), graph);
		return MCPToolResults.mcpResult(
			text,
			new JsonArray().add(MCPToolResults.reference("pipeline", uuid, result.version().getName())),
			new JsonArray().add(MCPToolResults.visual(VISUAL_TYPE, uuid, result.version().getName(),
				payload(result.pipeline().getUuid(), result.version(), graph))));
	}

	/**
	 * The text rendering of the graph. A client that ignores the visual — and the model itself, which only ever sees this text — must be able to
	 * describe the pipeline from it alone.
	 */
	public static String describe(String uuid, PipelineVersion version, JsonObject graph) {
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
