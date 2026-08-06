package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;
import static io.metaloom.loom.mcp.tool.MCPToolResults.visual;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
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
 *
 * <p>Resolution, projection and text rendering all live in {@link PipelineGraphRenderer}, which the authoring tools share so a pipeline the agent just
 * created is described exactly as one it looked up.</p>
 */
@Singleton
public class GetPipelineTool implements MCPTool {

	/**
	 * Visual type discriminator understood by the chat UI.
	 */
	public static final String VISUAL_TYPE = PipelineGraphRenderer.VISUAL_TYPE;

	public static final int MAX_NODES = PipelineGraphRenderer.MAX_NODES;

	public static final int MAX_EDGES = PipelineGraphRenderer.MAX_EDGES;

	private final DaoCollection daos;

	private final PipelineGraphRenderer renderer;

	@Inject
	public GetPipelineTool(DaoCollection daos, PipelineGraphRenderer renderer) {
		this.daos = daos;
		this.renderer = renderer;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"get_pipeline",
			"Load a single processing pipeline including its node graph (nodes and the port-to-port connections between them). "
				+ "Accepts a pipeline UUID or a pipeline name. Use this to show or explain how a pipeline processes media, and always call it "
				+ "before update_pipeline so you change the current graph rather than replacing it.",
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

			Pipeline pipeline = renderer.resolve(pipelineId.trim());
			if (pipeline == null) {
				return Future.succeededFuture(mcpTextResult("No pipeline found for: " + pipelineId));
			}
			PipelineVersion version = daos.pipelineVersionDao().loadLatestByPipeline(pipeline.getUuid());
			if (version == null) {
				return Future.succeededFuture(mcpTextResult("Pipeline " + pipeline.getUuid() + " has no version yet."));
			}

			JsonObject graph = renderer.graph(version);
			String uuid = pipeline.getUuid().toString();

			return Future.succeededFuture(mcpResult(
				PipelineGraphRenderer.describe(uuid, version, graph),
				new JsonArray().add(reference("pipeline", uuid, version.getName())),
				new JsonArray().add(visual(VISUAL_TYPE, uuid, version.getName(), renderer.payload(pipeline.getUuid(), version, graph)))));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
