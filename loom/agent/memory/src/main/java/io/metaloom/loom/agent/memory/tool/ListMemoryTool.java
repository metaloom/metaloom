package io.metaloom.loom.agent.memory.tool;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResultWithReferences;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPToolResults;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: list_memory
 *
 * <p>Lists the notes of the caller's memory scopes without loading any bodies.</p>
 */
@Singleton
public class ListMemoryTool extends AbstractMemoryTool {

	public static final String NAME = "list_memory";

	@Inject
	public ListMemoryTool(MemoryService memory) {
		super(memory);
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"List the notes in your persistent memory bank. Returns ids, titles and when each was last written — not the note contents; "
				+ "use get_memory to read one.",
			MCPToolDescriptor.buildInputSchema(List.of(
				scopeParam(true),
				refParam(),
				new MCPToolParam("prefix", "string", "Only list ids starting with this prefix, e.g. 'projects/'.", false),
				new MCPToolParam("limit", "integer", "Maximum number of notes to return (default: 50).", false))),
			List.of("READ_MEMORY"),
			true);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		try {
			JsonObject args = arguments == null ? new JsonObject() : arguments;
			String prefix = args.getString("prefix");
			int limit = args.getInteger("limit", 50);

			List<MemoryScopeRef> available = memory.scopes().resolve(ctx);
			if (available.isEmpty()) {
				return Future.succeededFuture(MCPToolResults.mcpTextResult("No memory scopes are available in this conversation."));
			}

			MemoryScope requested = MemoryScope.parse(args.getString(ARG_SCOPE));
			List<MemoryScopeRef> selected = isAll(args.getString(ARG_SCOPE))
				? available
				: List.of(memory.scopes().select(available, requested, args.getString(ARG_REF)));

			List<MemoryEntry> entries = new ArrayList<>();
			for (MemoryScopeRef scope : selected) {
				entries.addAll(memory.list(scope, prefix, limit));
			}

			StringBuilder text = new StringBuilder();
			text.append("Memory scopes available: ")
				.append(available.stream().map(MemoryScopeRef::ref).reduce((a, b) -> a + ", " + b).orElse("none"))
				.append(".\n");
			if (entries.isEmpty()) {
				text.append("No notes stored yet. Use put_memory to record a durable fact.");
				return Future.succeededFuture(MCPToolResults.mcpTextResult(text.toString()));
			}

			text.append(entries.size()).append(" note(s):\n");
			JsonArray references = new JsonArray();
			for (MemoryEntry entry : entries) {
				text.append("- ").append(describe(entry)).append('\n');
				references.add(MCPToolResults.reference("memory", entry.getUuid().toString(),
					entry.getScope().key() + ":" + entry.getMemoryId()));
			}
			return Future.succeededFuture(mcpResultWithReferences(text.toString(), references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	/**
	 * {@code all} is the default: with no scope argument the model sees everything it may reach.
	 */
	private static boolean isAll(String scope) {
		return scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope.strip());
	}

}
