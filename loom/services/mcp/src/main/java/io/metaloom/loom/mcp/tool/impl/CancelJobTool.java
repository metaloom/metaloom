package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.service.impl.NodeRunService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: cancel_job
 *
 * <p>
 * Stops an ad-hoc run. Work already in flight on a worker is not recalled - the dispatcher has no
 * reverse signal - so those tasks settle normally and simply spawn nothing further; what stops is
 * everything that had not been dispatched yet.
 * </p>
 *
 * <p>
 * A run belonging to somebody else is reported as not found, for the same reason {@code get_job} does
 * it: telling the caller that a uuid exists but is not theirs is itself the leak.
 * </p>
 */
@Singleton
public class CancelJobTool implements MCPTool {

	public static final String NAME = "cancel_job";

	private final NodeRunService nodeRunService;

	@Inject
	public CancelJobTool(NodeRunService nodeRunService) {
		this.nodeRunService = nodeRunService;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Stop a node run started with run_node_graph. Nodes already running on a worker finish, but nothing further is started.",
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

		try {
			nodeRunService.cancel(ctx.userUuid(), jobId);
		} catch (LoomRestException e) {
			if (e.httpCode() == 404) {
				return Future.succeededFuture(mcpTextResult("There is no node run " + jobId + " belonging to you."));
			}
			// Typically "it already finished", which is an answer, not a failure.
			return Future.succeededFuture(mcpTextResult("Could not cancel node run " + jobId + ": " + e.getMessage()));
		} catch (Exception e) {
			return Future.succeededFuture(mcpTextResult("ERROR: " + e.getMessage()));
		}

		return Future.succeededFuture(mcpTextResult("Cancelled node run " + jobId
			+ ". Nodes already running on a worker will finish; nothing further will start."));
	}

}
