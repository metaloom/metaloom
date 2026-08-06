package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService.PipelineWithVersion;
import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: create_pipeline
 *
 * <p>
 * Stores a new pipeline and its version 1 through {@link PipelineAuthoringService}, the same write path the REST create endpoint uses — so a pipeline
 * the agent authored is indistinguishable from one drawn in the editor, and there is no second place where {@code latest_version_uuid} is maintained.
 * </p>
 *
 * <p>
 * 🔴 <b>Identity-scoped.</b> A pipeline has a creator, so this tool declares {@code requiresIdentity} and is therefore never bound to an EventBus
 * address — the only way to reach it is {@code MCPToolRegistry.dispatch} with a resolved caller. The consequence is deliberate: with MCP
 * authentication disabled an external client is anonymous and cannot author anything, while the in-process chat agent, which always resolves a caller
 * from the chat, can.
 * </p>
 */
@Singleton
public class CreatePipelineTool implements MCPTool {

	public static final String NAME = "create_pipeline";

	private final PipelineAuthoringService authoring;

	private final PipelineGraphRenderer renderer;

	@Inject
	public CreatePipelineTool(PipelineAuthoringService authoring, PipelineGraphRenderer renderer) {
		this.authoring = authoring;
		this.renderer = renderer;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Store a new processing pipeline. Call validate_pipeline on the definition first and only call this once it answers VALID — "
				+ "a rejected definition wastes a round trip and stores nothing. The pipeline is created as version 1 and is NOT run; "
				+ "starting a run is a separate action the user takes.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("name", "string", "Name of the pipeline", true),
				new MCPToolParam("definition", "object", "The pipeline definition JSON: {version, nodes[], edges[]}", true),
				new MCPToolParam("description", "string", "What this pipeline is for", false),
				new MCPToolParam("enabled", "boolean", "Whether the pipeline is enabled (default true)", false),
				new MCPToolParam("dryRun", "boolean", "Whether runs of this pipeline are dry runs (default false)", false),
				new MCPToolParam("priority", "integer", "Scheduling priority (default 0)", false))),
			List.of("CREATE_PIPELINE", "CREATE_MCP_PIPELINE"),
			true);
	}

	/**
	 * Unreachable by construction — an identity-scoped tool has no EventBus address. Failing loudly rather than quietly creating a pipeline with no
	 * creator is the point.
	 */
	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(NAME + " requires an authenticated caller and cannot be dispatched without one.");
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		try {
			String name = arguments.getString("name");
			if (name == null || name.isBlank()) {
				return Future.succeededFuture(mcpTextResult("ERROR: The name parameter is required."));
			}
			JsonObject definition = arguments.getJsonObject("definition");
			if (definition == null) {
				return Future.succeededFuture(mcpTextResult("ERROR: The definition parameter is required and must be a JSON object."));
			}

			PipelineCreateRequest request = new PipelineCreateRequest()
				.setName(name.trim())
				.setDescription(arguments.getString("description"))
				.setDefinition(definition)
				.setEnabled(arguments.getBoolean("enabled"))
				.setDryRun(arguments.getBoolean("dryRun"))
				.setPriority(arguments.getInteger("priority"));

			PipelineWithVersion created;
			try {
				created = authoring.create(ctx.userUuid(), request);
			} catch (ValidationException e) {
				// The definition was rejected before anything was written. Report it the way
				// validate_pipeline does so the model can act on it rather than on a JSON-RPC error.
				return Future.succeededFuture(mcpTextResult("INVALID: " + e.getMessage()
					+ "\n\nNothing was stored. Fix the definition and validate it with validate_pipeline before trying again."));
			}

			return Future.succeededFuture(renderer.describeWrite(created, "Created pipeline"));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
