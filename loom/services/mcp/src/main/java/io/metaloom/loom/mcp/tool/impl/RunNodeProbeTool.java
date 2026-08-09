package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.NodeExecOptions;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.model.noderun.NodeProbeRequest;
import io.metaloom.loom.rest.model.noderun.NodeProbeResponse;
import io.metaloom.loom.rest.service.impl.NodeResultRenderer;
import io.metaloom.loom.rest.service.impl.NodeRunService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: run_node_probe
 *
 * <p>
 * Runs one processing node against one asset and returns what it produced, inside the turn. This is
 * the "gather data" case - ask a vision model about an image, hash a file, read a document - and it
 * deliberately does not touch the catalog: the result comes back as text and nothing is recorded
 * unless {@code persist} is set.
 * </p>
 *
 * <p>
 * The probe is bounded by {@code LOOM_AGENT_PROBE_TIMEOUT_MS}, which is configured below the tool
 * timeout so that slow work reports itself as a readable result naming the node - something the model
 * can act on - rather than as a transport timeout it cannot.
 * </p>
 *
 * <p>
 * 🔴 <b>Identity-scoped.</b> Running a node spends worker and GPU time and is attributed to a user, so
 * this tool is never bound to an EventBus address; the only way to reach it is
 * {@code MCPToolRegistry.dispatch} with a resolved caller.
 * </p>
 */
@Singleton
public class RunNodeProbeTool implements MCPTool {

	public static final String NAME = "run_node_probe";

	private final NodeRunService nodeRunService;
	private final NodeExecOptions options;

	@Inject
	public RunNodeProbeTool(NodeRunService nodeRunService, LoomOptions loomOptions) {
		this.nodeRunService = nodeRunService;
		this.options = loomOptions.getNodeExec();
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Run a single processing node against a single asset and get the result back now. Use it to gather information - "
				+ "describe an image, transcribe a clip, hash a file - not to process a set. For more than one asset or more than "
				+ "one node use run_node_graph, which returns a job handle. Nothing is written to the asset unless persist is true.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("kind", "string", "The node kind to run, e.g. sha512 or vlm. Use list_nodes to see what exists.", true),
				new MCPToolParam("assetUuid", "string", "The asset to run the node against", true),
				new MCPToolParam("options", "object", "Node options, validated against the node's declared parameters", false),
				new MCPToolParam("persist", "boolean", "Record the result on the asset (default false, which writes nothing)", false))),
			List.of("READ_ASSET", "EXECUTE_MCP_NODE"),
			true);
	}

	/** Unreachable by construction: an identity-scoped tool has no EventBus address. */
	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(NAME + " requires an authenticated caller and cannot be dispatched without one.");
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		String kind = arguments.getString("kind");
		if (kind == null || kind.isBlank()) {
			return Future.succeededFuture(mcpTextResult("ERROR: The kind parameter is required."));
		}
		UUID assetUuid;
		try {
			assetUuid = UUID.fromString(arguments.getString("assetUuid", ""));
		} catch (IllegalArgumentException e) {
			return Future.succeededFuture(mcpTextResult("ERROR: assetUuid must be a uuid."));
		}

		NodeProbeRequest request = new NodeProbeRequest()
			.setKind(kind)
			.setAssetUuid(assetUuid)
			.setPersist(arguments.getBoolean("persist"));
		JsonObject nodeOptions = arguments.getJsonObject("options");
		if (nodeOptions != null) {
			request.setOptions((Map<String, Object>) nodeOptions.getMap());
		}

		try {
			return nodeRunService.probe(ctx.userUuid(), request)
				.map(this::render)
				// A refusal the service could not turn into a response - a malformed request, say - still
				// has to reach the model as something it can read, not as a JSON-RPC error string.
				.otherwise(e -> mcpTextResult("ERROR: " + e.getMessage()));
		} catch (Exception e) {
			return Future.succeededFuture(mcpTextResult("ERROR: " + e.getMessage()));
		}
	}

	private JsonObject render(NodeProbeResponse response) {
		StringBuilder text = new StringBuilder();
		if (NodeRunService.STATE_REJECTED.equals(response.getState())) {
			text.append("Could not run '").append(response.getNodeKind()).append("': ").append(response.getMessage());
		} else {
			text.append(response.getNodeKind()).append(" -> ").append(response.getState());
			if (response.getDurationMs() != null) {
				text.append(" (").append(response.getDurationMs()).append("ms)");
			}
			text.append('\n').append(response.getText());
		}
		// TODO(CTX3): fold into the global tool-result cap once LOOM_AI_TOOL_RESULT_MAX_CHARS exists.
		return mcpTextResult(NodeResultRenderer.cap(text.toString(), options.getResultMaxChars()));
	}

}
