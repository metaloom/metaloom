package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.visual;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.NodeExecOptions;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.model.noderun.NodeRunItemResult;
import io.metaloom.loom.rest.model.noderun.NodeRunStatusResponse;
import io.metaloom.loom.rest.service.impl.NodeResultRenderer;
import io.metaloom.loom.rest.service.impl.NodeRunService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: get_job
 *
 * <p>
 * Reads the status and results of an ad-hoc run started by {@code run_node_graph}. This is the other
 * half of the async model: the run outlives the turn that started it, and this is how a later turn
 * picks the answer up.
 * </p>
 *
 * <p>
 * A run belonging to somebody else is reported as <b>not found</b>, never as forbidden - the
 * distinction would let a caller enumerate other people's jobs by uuid.
 * </p>
 */
@Singleton
public class GetJobTool implements MCPTool {

	public static final String NAME = "get_job";

	/** Per-item lines rendered before the rest are summarised. A model does not need two hundred rows. */
	private static final int MAX_ROWS = 25;

	private final NodeRunService nodeRunService;
	private final NodeExecOptions options;

	@Inject
	public GetJobTool(NodeRunService nodeRunService, LoomOptions loomOptions) {
		this.nodeRunService = nodeRunService;
		this.options = loomOptions.getNodeExec();
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Read the status and results of a node run started with run_node_graph. A run that is still going reports partial results; "
				+ "call again in a later turn for the rest.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("jobId", "string", "The run id returned by run_node_graph", true))),
			List.of("EXECUTE_MCP_NODE"),
			true);
	}

	/** Unreachable by construction: an identity-scoped tool has no EventBus address. */
	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(NAME + " requires an authenticated caller and cannot be dispatched without one.");
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		UUID jobId;
		try {
			jobId = UUID.fromString(arguments.getString("jobId", ""));
		} catch (IllegalArgumentException e) {
			return Future.succeededFuture(mcpTextResult("ERROR: jobId must be a uuid."));
		}

		NodeRunStatusResponse status;
		try {
			status = nodeRunService.status(ctx.userUuid(), jobId, true);
		} catch (LoomRestException e) {
			return Future.succeededFuture(mcpTextResult("There is no node run " + jobId + " belonging to you."));
		} catch (Exception e) {
			return Future.succeededFuture(mcpTextResult("ERROR: " + e.getMessage()));
		}

		return Future.succeededFuture(mcpResult(render(status), null, new JsonArray().add(jobCard(status))));
	}

	private String render(NodeRunStatusResponse status) {
		StringBuilder text = new StringBuilder()
			.append("Node run ").append(status.getUuid()).append(": ").append(status.getStatus())
			.append(" - ").append(status.getMediaCount()).append(" item(s), ")
			.append(status.getSuccessCount()).append(" succeeded, ")
			.append(status.getFailureCount()).append(" failed, ")
			.append(status.getSkippedCount()).append(" skipped.\n");
		if (status.getErrorMessage() != null) {
			text.append("Error: ").append(status.getErrorMessage()).append('\n');
		}

		List<NodeRunItemResult> results = status.getResults();
		if (results == null || results.isEmpty()) {
			text.append("No node has settled yet.");
			return NodeResultRenderer.cap(text.toString(), options.getResultMaxChars());
		}

		int shown = Math.min(results.size(), MAX_ROWS);
		for (int i = 0; i < shown; i++) {
			NodeRunItemResult result = results.get(i);
			text.append("  ").append(filenameOf(result.getMediaPath()))
				.append(" / ").append(result.getNodeId())
				.append(": ").append(result.getState());
			if (result.getMessage() != null && !result.getMessage().isBlank()) {
				text.append(" - ").append(result.getMessage());
			}
			if (result.getOutputs() != null && !result.getOutputs().isEmpty()) {
				text.append(" -> ").append(summarise(result.getOutputs()));
			}
			text.append('\n');
		}
		if (results.size() > shown) {
			// Said out loud rather than silently dropped: a truncated list that does not say it is
			// truncated reads as the complete answer.
			text.append("  ... and ").append(results.size() - shown)
				.append(" more. Read the full results at GET /api/v1/node-runs/").append(status.getUuid()).append('\n');
		}
		// TODO(CTX3): fold into the global tool-result cap once LOOM_AI_TOOL_RESULT_MAX_CHARS exists.
		return NodeResultRenderer.cap(text.toString(), options.getResultMaxChars());
	}

	/** One line per port: the type and the first value, which is what a model actually reasons over. */
	private static String summarise(JsonObject outputs) {
		StringBuilder summary = new StringBuilder();
		for (String port : outputs.fieldNames()) {
			JsonObject payload = outputs.getJsonObject(port);
			if (payload == null) {
				continue;
			}
			JsonArray elements = payload.getJsonArray("elements", new JsonArray());
			if (summary.length() > 0) {
				summary.append("; ");
			}
			summary.append(port).append('=');
			if (elements.isEmpty()) {
				summary.append("(empty)");
			} else {
				summary.append(String.valueOf(elements.getJsonObject(0).getValue("value")));
				if (elements.size() > 1) {
					summary.append(" +").append(elements.size() - 1);
				}
			}
		}
		return summary.toString();
	}

	private static JsonObject jobCard(NodeRunStatusResponse status) {
		int settled = status.getSuccessCount() + status.getFailureCount() + status.getSkippedCount();
		int total = Math.max(status.getMediaCount(), settled);
		return visual("job-card", String.valueOf(status.getUuid()), "Node run",
			new JsonObject()
				.put("status", status.getStatus())
				.put("total", total)
				.put("settled", settled)
				.put("success", status.getSuccessCount())
				.put("failure", status.getFailureCount())
				.put("skipped", status.getSkippedCount())
				.put("percent", total == 0 ? 0 : (settled * 100) / total)
				.put("cancellable", status.getFinished() == null));
	}

	private static String filenameOf(String path) {
		if (path == null) {
			return "(unknown)";
		}
		int slash = path.lastIndexOf('/');
		return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
	}

}
