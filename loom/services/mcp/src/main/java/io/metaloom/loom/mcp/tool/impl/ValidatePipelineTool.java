package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService.ValidationReport;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: validate_pipeline
 *
 * <p>
 * The dry run. It answers the same question {@code create_pipeline} answers on the way in — would this definition be accepted, and would it run as
 * drawn — and stores nothing, so an agent can iterate on a draft without leaving a pipeline behind for every attempt.
 * </p>
 *
 * <p>
 * It runs through {@link PipelineAuthoringService#validate(JsonObject)}, which is the very code path the create path uses: a draft that validates here
 * is a draft that saves, and that equivalence is the only reason the tool is worth having.
 * </p>
 */
@Singleton
public class ValidatePipelineTool implements MCPTool {

	private final PipelineAuthoringService authoring;

	@Inject
	public ValidatePipelineTool(PipelineAuthoringService authoring) {
		this.authoring = authoring;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"validate_pipeline",
			"Check a pipeline definition without storing anything. Reports every problem it finds — an unknown node kind, a port that "
				+ "does not exist, incompatible content types, an unwired required input, a cycle, an unreachable node. ALWAYS call this on a "
				+ "draft and fix what it reports until it answers VALID, before calling create_pipeline or update_pipeline. Warnings are not "
				+ "errors: a definition can be VALID and still warn that no worker is online for one of its node kinds.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("definition", "object", "The pipeline definition JSON: {version, nodes[], edges[]}", true))),
			List.of("READ_PIPELINE", "VALIDATE_MCP_PIPELINE"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			JsonObject definition = arguments.getJsonObject("definition");
			if (definition == null) {
				return Future.succeededFuture(mcpTextResult("ERROR: The definition parameter is required and must be a JSON object."));
			}
			return Future.succeededFuture(mcpTextResult(render(authoring.validate(definition))));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	/**
	 * A rejected definition is a <b>result</b>, not a tool failure: a failed future collapses into a JSON-RPC error string, and the whole point here is
	 * to hand the model something specific enough to act on.
	 */
	static String render(ValidationReport report) {
		StringBuilder sb = new StringBuilder();
		if (!report.valid()) {
			sb.append("INVALID: ").append(report.error()).append("\n");
			// Everything after the first is listed too, but separately: a model that reads six
			// messages as one problem tends to rewrite six things. Later errors can also be
			// consequences of the first, which is why the first keeps its own line.
			if (report.errors().size() > 1) {
				sb.append("\nAlso reported:\n");
				report.errors().subList(1, report.errors().size())
					.forEach(error -> sb.append("- ").append(error.getMessage()).append("\n"));
			}
			sb.append("\nFix these and validate again.\n");
			return sb.toString();
		}
		sb.append("VALID — this definition would be accepted.\n");
		if (!report.warnings().isEmpty()) {
			sb.append("\nWarnings (not blocking):\n");
			for (String warning : report.warnings()) {
				sb.append("- ").append(warning).append("\n");
			}
		}
		return sb.toString();
	}

}
