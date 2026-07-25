package io.metaloom.loom.agent.memory.tool;

import java.util.List;

import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * Shared plumbing of the four memory tools: scope-argument handling and the reminder that the identity-free
 * {@link MCPTool#execute(JsonObject)} overload must never be reachable for them.
 */
public abstract class AbstractMemoryTool implements MCPTool {

	protected static final String ARG_SCOPE = "scope";

	protected static final String ARG_REF = "ref";

	protected static final String ARG_ID = "id";

	protected final MemoryService memory;

	protected AbstractMemoryTool(MemoryService memory) {
		this.memory = memory;
	}

	/**
	 * Memory tools are identity-scoped: {@code MCPToolRegistry} never registers an EventBus address for them and always calls
	 * {@link #execute(JsonObject, MCPCallerContext)}. Reaching this overload would mean the caller identity was lost, which must fail loudly rather than
	 * silently operate on the wrong scope.
	 */
	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(getClass().getSimpleName() + " requires an authenticated caller and cannot be dispatched without one.");
	}

	/**
	 * The scope parameter shared by every memory tool.
	 *
	 * @param includeAll
	 *            Whether {@code all} is an accepted value (only {@code list_memory} spans scopes)
	 */
	protected static MCPToolParam scopeParam(boolean includeAll) {
		List<String> values = includeAll
			? List.of("user", "group", "space", "all")
			: List.of("user", "group", "space");
		return new MCPToolParam(ARG_SCOPE, "string",
			"Which memory scope to use. 'user' (default) is private to you; 'space' is shared with the project/space of this chat; "
				+ "'group' is shared with a group you belong to."
				+ (includeAll ? " 'all' spans every scope available here." : ""),
			false, values);
	}

	protected static MCPToolParam refParam() {
		return new MCPToolParam(ARG_REF, "string",
			"Which group or space to use, by name, when more than one is available. Ignored for the 'user' scope.", false);
	}

	/**
	 * Resolve the scope named by the arguments against the caller's available set.
	 */
	protected MemoryScopeRef resolveScope(JsonObject args, MCPCallerContext ctx) {
		List<MemoryScopeRef> available = memory.scopes().resolve(ctx);
		MemoryScope scope = MemoryScope.parse(args.getString(ARG_SCOPE));
		return memory.scopes().select(available, scope, args.getString(ARG_REF));
	}

	/**
	 * Render one index line: {@code user:projects/loom-db.md — "Loom DB notes" (v3, updated …, session "…", 1.2 KB)}.
	 */
	protected static String describe(MemoryEntry entry) {
		StringBuilder sb = new StringBuilder();
		sb.append(entry.getScope().key()).append(':').append(entry.getMemoryId());
		if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
			sb.append(" — \"").append(entry.getTitle()).append('"');
		}
		sb.append(" (v").append(entry.getVersion());
		if (entry.getEdited() != null) {
			sb.append(", updated ").append(entry.getEdited().toString(), 0, 10);
		}
		if (entry.getSessionName() != null && !entry.getSessionName().isBlank()) {
			sb.append(", session \"").append(entry.getSessionName()).append('"');
		}
		sb.append(", ").append(humanSize(entry.getSize())).append(')');
		return sb.toString();
	}

	protected static String humanSize(int bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		return String.format("%.1f KB", bytes / 1024.0);
	}

}
