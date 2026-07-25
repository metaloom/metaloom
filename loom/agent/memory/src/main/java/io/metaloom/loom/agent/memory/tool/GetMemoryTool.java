package io.metaloom.loom.agent.memory.tool;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResultWithReferences;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPToolResults;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: get_memory
 *
 * <p>Reads one note. Content from a shared scope is delimited and labelled with its author — it is data written by another user, not instructions.</p>
 */
@Singleton
public class GetMemoryTool extends AbstractMemoryTool {

	public static final String NAME = "get_memory";

	@Inject
	public GetMemoryTool(MemoryService memory) {
		super(memory);
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Read one note from your persistent memory bank by its id (a path like 'projects/loom-db.md'). "
				+ "Read a note before relying on it — the listing only shows titles.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam(ARG_ID, "string", "The note id, e.g. 'preferences.md' or 'projects/loom-db.md'.", true),
				scopeParam(false),
				refParam(),
				new MCPToolParam("includeHeader", "boolean", "Return the raw file including its frontmatter header (default: false).", false))),
			List.of("READ_MEMORY"),
			true);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		try {
			JsonObject args = arguments == null ? new JsonObject() : arguments;
			MemoryScopeRef scope = resolveScope(args, ctx);
			MemoryEntry entry = memory.loadOrFail(scope, args.getString(ARG_ID));
			String text = memory.renderForModel(entry, args.getBoolean("includeHeader", false));

			JsonArray references = new JsonArray().add(MCPToolResults.reference("memory", entry.getUuid().toString(),
				entry.getScope().key() + ":" + entry.getMemoryId()));
			return Future.succeededFuture(mcpResultWithReferences(text, references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
