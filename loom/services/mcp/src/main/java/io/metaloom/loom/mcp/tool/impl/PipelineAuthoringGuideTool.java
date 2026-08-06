package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.common.skill.BuiltinSkill;
import io.metaloom.loom.common.skill.BuiltinSkills;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: pipeline_authoring_guide
 *
 * <p>
 * Serves the {@code pipeline-authoring} built-in skill over MCP. The Loom chat agent reaches that text through {@code load_skill}, but an external MCP
 * client has no notion of a skill at all — and the rules it carries (edges are port-to-port, exactly one source, the assignability lattice) are not
 * something a model can be expected to know. Without this, the authoring tools are usable only by an agent that already knows how to use them.
 * </p>
 *
 * <p>
 * The body is the same classpath resource in both cases, so the two audiences can never drift apart.
 * </p>
 */
@Singleton
public class PipelineAuthoringGuideTool implements MCPTool {

	public static final String NAME = "pipeline_authoring_guide";

	@Inject
	public PipelineAuthoringGuideTool() {
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Read the guide to authoring Loom pipeline definitions: the shape of the definition JSON, how nodes are wired port-to-port, "
				+ "the rules the validator enforces, and the order to call the pipeline tools in. Read this BEFORE writing your first "
				+ "definition — the format has rules that cannot be guessed from an example.",
			MCPToolDescriptor.buildInputSchema(List.of()),
			List.of("READ_PIPELINE"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		BuiltinSkill skill = BuiltinSkills.byName(BuiltinSkills.PIPELINE_AUTHORING);
		if (skill == null) {
			return Future.failedFuture("The pipeline authoring guide is missing from this build.");
		}
		return Future.succeededFuture(mcpTextResult(skill.content()));
	}

}
