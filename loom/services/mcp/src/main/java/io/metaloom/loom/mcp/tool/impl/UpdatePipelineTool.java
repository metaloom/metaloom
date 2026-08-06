package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService.PipelineWithVersion;
import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: update_pipeline
 *
 * <p>
 * Appends a new version to an existing pipeline through {@link PipelineAuthoringService}. An existing version is never mutated — that is what makes
 * the version list a history rather than a record of the current state — so a change the agent gets wrong is one restore away from undone.
 * </p>
 *
 * <p>
 * Every field is optional and an unset one is carried forward, which is why the tool description tells the model to read the pipeline first: passing a
 * {@code definition} replaces the whole graph, and an agent that meant to add one node can otherwise delete the rest of them without noticing.
 * </p>
 */
@Singleton
public class UpdatePipelineTool implements MCPTool {

	public static final String NAME = "update_pipeline";

	private final PipelineAuthoringService authoring;

	private final PipelineGraphRenderer renderer;

	@Inject
	public UpdatePipelineTool(PipelineAuthoringService authoring, PipelineGraphRenderer renderer) {
		this.authoring = authoring;
		this.renderer = renderer;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Change an existing pipeline by storing a new version of it. Fields you leave out keep their current value. "
				+ "The definition REPLACES the whole graph, so call get_pipeline first and send the complete modified graph — "
				+ "sending only the nodes you want to add deletes the others. Validate the definition with validate_pipeline first. "
				+ "Previous versions are kept and can be restored.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("pipelineId", "string", "Pipeline UUID or pipeline name (case-insensitive)", true),
				new MCPToolParam("definition", "object", "The complete replacement definition JSON: {version, nodes[], edges[]}", false),
				new MCPToolParam("name", "string", "New name for the pipeline", false),
				new MCPToolParam("description", "string", "New description", false),
				new MCPToolParam("enabled", "boolean", "Whether the pipeline is enabled", false),
				new MCPToolParam("dryRun", "boolean", "Whether runs of this pipeline are dry runs", false),
				new MCPToolParam("priority", "integer", "Scheduling priority", false))),
			List.of("UPDATE_PIPELINE", "UPDATE_MCP_PIPELINE"),
			true);
	}

	/**
	 * Unreachable by construction — an identity-scoped tool has no EventBus address.
	 */
	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(NAME + " requires an authenticated caller and cannot be dispatched without one.");
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		try {
			String pipelineId = arguments.getString("pipelineId");
			if (pipelineId == null || pipelineId.isBlank()) {
				return Future.succeededFuture(mcpTextResult("ERROR: The pipelineId parameter is required."));
			}
			Pipeline pipeline = renderer.resolve(pipelineId.trim());
			if (pipeline == null) {
				return Future.succeededFuture(mcpTextResult("No pipeline found for: " + pipelineId));
			}

			PipelineUpdateRequest request = new PipelineUpdateRequest()
				.setName(arguments.getString("name"))
				.setDescription(arguments.getString("description"))
				.setDefinition(arguments.getJsonObject("definition"))
				.setEnabled(arguments.getBoolean("enabled"))
				.setDryRun(arguments.getBoolean("dryRun"))
				.setPriority(arguments.getInteger("priority"));

			PipelineWithVersion updated;
			try {
				updated = authoring.update(ctx.userUuid(), pipeline.getUuid(), request);
			} catch (ValidationException e) {
				return Future.succeededFuture(mcpTextResult("INVALID: " + e.getMessage()
					+ "\n\nNothing was stored and the pipeline is unchanged. Fix the definition and check it with validate_pipeline."));
			}
			if (updated == null) {
				// Resolved a moment ago, gone now — report it rather than pretending it worked.
				return Future.succeededFuture(mcpTextResult("No pipeline found for: " + pipelineId));
			}

			return Future.succeededFuture(renderer.describeWrite(updated, "Updated pipeline"));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
