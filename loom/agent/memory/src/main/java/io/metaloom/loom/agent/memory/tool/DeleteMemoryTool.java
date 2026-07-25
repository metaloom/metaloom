package io.metaloom.loom.agent.memory.tool;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPToolResults;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: delete_memory
 *
 * <p>Removes one note. There is no version history yet, so a delete is final — the description says so, because the model must not treat this as a
 * reversible tidy-up.</p>
 */
@Singleton
public class DeleteMemoryTool extends AbstractMemoryTool {

	public static final String NAME = "delete_memory";

	@Inject
	public DeleteMemoryTool(MemoryService memory) {
		super(memory);
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Delete a note from your persistent memory bank. This is permanent — there is no version history. Only delete a note when it is wrong or "
				+ "obsolete, and never to 'tidy up' notes in a shared scope, which other people rely on.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam(ARG_ID, "string", "The note id to delete, e.g. 'projects/loom-db.md'.", true),
				scopeParam(false),
				refParam())),
			List.of("DELETE_MEMORY"),
			true);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		try {
			JsonObject args = arguments == null ? new JsonObject() : arguments;
			MemoryScopeRef scope = resolveScope(args, ctx);
			String id = args.getString(ARG_ID);
			boolean deleted = memory.delete(ctx, scope, id);
			String text = deleted
				? "Deleted " + scope.scope().key() + ":" + id + "."
				: "No memory entry '" + id + "' in scope " + scope.ref() + " — nothing to delete.";
			return Future.succeededFuture(MCPToolResults.mcpTextResult(text));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
