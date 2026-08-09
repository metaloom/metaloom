package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.visual;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.model.noderun.NodeRunRequest;
import io.metaloom.loom.rest.model.noderun.NodeRunResponse;
import io.metaloom.loom.rest.service.impl.NodeRunService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: run_node_graph
 *
 * <p>
 * Starts an ad-hoc run over a set of assets and returns a handle immediately. This is the answer to
 * the mismatch between a tool call, which has seconds, and a vision pass over two hundred images,
 * which has minutes: the model gets a job id inside its turn and reads the outcome later through
 * {@code get_job}, or the user is told by a notification when it finishes.
 * </p>
 *
 * <p>
 * The definition is the same JSON {@code validate_pipeline} accepts, so a model should validate a
 * graph before spending anything on it. The source node may be omitted - Loom supplies the media from
 * the asset uuids.
 * </p>
 */
@Singleton
public class RunNodeGraphTool implements MCPTool {

	public static final String NAME = "run_node_graph";

	private final NodeRunService nodeRunService;

	@Inject
	public RunNodeGraphTool(NodeRunService nodeRunService) {
		this.nodeRunService = nodeRunService;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Run a graph of processing nodes over a set of assets. Returns a job id straight away - the work continues after this turn. "
				+ "Poll it with get_job, or stop it with cancel_job. Validate the definition with validate_pipeline first. "
				+ "For a single node against a single asset use run_node_probe instead, which returns the result directly.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("definition", "object", "The graph to run: {version, nodes[], edges[]}. The source node may be omitted.", true),
				new MCPToolParam("assetUuids", "array", "The assets to run the graph against", true),
				new MCPToolParam("persist", "boolean", "Record results on the assets (default false, which writes nothing)", false),
				new MCPToolParam("dryRun", "boolean", "Run without letting nodes take effect, to see what would happen", false))),
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
		JsonObject definition = arguments.getJsonObject("definition");
		if (definition == null) {
			return Future.succeededFuture(mcpTextResult("ERROR: The definition parameter is required and must be a JSON object."));
		}
		JsonArray rawAssets = arguments.getJsonArray("assetUuids");
		if (rawAssets == null || rawAssets.isEmpty()) {
			return Future.succeededFuture(mcpTextResult("ERROR: At least one assetUuid is required."));
		}
		List<UUID> assetUuids = new ArrayList<>();
		for (Object raw : rawAssets) {
			try {
				assetUuids.add(UUID.fromString(String.valueOf(raw)));
			} catch (IllegalArgumentException e) {
				return Future.succeededFuture(mcpTextResult("ERROR: '" + raw + "' is not a uuid."));
			}
		}

		NodeRunRequest request = new NodeRunRequest()
			.setDefinition(definition)
			.setAssetUuids(assetUuids)
			.setPersist(arguments.getBoolean("persist"))
			.setDryRun(arguments.getBoolean("dryRun"));

		NodeRunResponse response;
		try {
			response = nodeRunService.startRun(ctx.userUuid(), request);
		} catch (LoomRestException e) {
			// A quota, an invalid graph or an unavailable node kind. All of these are answers the model
			// can work with - narrow the set, fix the graph, pick another node - so they come back as a
			// result rather than as a failed future the loop can only report verbatim.
			return Future.succeededFuture(mcpTextResult("Could not start the run: " + e.getMessage()));
		} catch (Exception e) {
			return Future.succeededFuture(mcpTextResult("ERROR: " + e.getMessage()));
		}

		StringBuilder text = new StringBuilder()
			.append("Started node run ").append(response.getUuid())
			.append(" over ").append(response.getAccepted()).append(" asset(s).");
		if (response.getRejected() > 0) {
			text.append(' ').append(response.getRejected())
				.append(" asset(s) could not be included because they have no stored file.");
		}
		if (response.getEtaMs() != null) {
			text.append(" Rough estimate: ").append(Math.max(1, response.getEtaMs() / 1000)).append("s.");
		}
		text.append(" It keeps running after this turn - call get_job with the id above to read the results.");

		return Future.succeededFuture(mcpResult(text.toString(), null, new JsonArray().add(jobCard(response))));
	}

	/**
	 * The job card the chat renders.
	 *
	 * <p>
	 * The counters are duplicated in the text on purpose: the model never sees the visuals payload, so
	 * anything only in the card is invisible to it.
	 * </p>
	 */
	private static JsonObject jobCard(NodeRunResponse response) {
		return visual("job-card", String.valueOf(response.getUuid()), "Node run",
			new JsonObject()
				.put("status", response.getStatus())
				.put("total", response.getAccepted())
				.put("settled", 0)
				.put("success", 0)
				.put("failure", 0)
				.put("skipped", 0)
				.put("percent", 0)
				.put("cancellable", true));
	}

}
