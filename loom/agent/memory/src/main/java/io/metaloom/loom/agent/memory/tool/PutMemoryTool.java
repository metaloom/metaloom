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
 * MCP tool: put_memory
 *
 * <p>Creates or overwrites one note. This is the <b>only</b> way to change memory — the materialized {@code /memory} folder is read-only.</p>
 */
@Singleton
public class PutMemoryTool extends AbstractMemoryTool {

	public static final String NAME = "put_memory";

	@Inject
	public PutMemoryTool(MemoryService memory) {
		super(memory);
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Store a note in your persistent memory bank so it is available in later conversations. This is the ONLY way to change memory — the "
				+ "/memory folder is read-only and edits made there are discarded. Overwrites the note if the id already exists. "
				+ "Record durable facts only (decisions, conventions, stable structure); never transient chat state or secrets. "
				+ "Provide the markdown body only — the provenance header is added automatically.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam(ARG_ID, "string",
					"The note id: a lowercase relative path ending in '.md', e.g. 'preferences.md' or 'projects/loom-db.md'.", true),
				new MCPToolParam("content", "string", "The markdown body of the note.", true),
				scopeParam(false),
				refParam(),
				new MCPToolParam("title", "string", "Short human readable title. Defaults to the file name.", false))),
			List.of("UPDATE_MEMORY"),
			true);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		try {
			JsonObject args = arguments == null ? new JsonObject() : arguments;
			MemoryScopeRef scope = resolveScope(args, ctx);
			MemoryEntry entry = memory.put(ctx, scope, args.getString(ARG_ID), args.getString("content", ""), args.getString("title"));

			StringBuilder text = new StringBuilder();
			text.append("Stored ").append(entry.getScope().key()).append(':').append(entry.getMemoryId())
				.append(" (v").append(entry.getVersion()).append(", ").append(humanSize(entry.getSize())).append(").");
			if (memory.cfg().isMountEnabled()) {
				text.append(" Readable at ").append(memory.cfg().getMountPath()).append('/')
					.append(scope.directory()).append('/').append(entry.getMemoryId()).append('.');
			}

			JsonArray references = new JsonArray().add(MCPToolResults.reference("memory", entry.getUuid().toString(),
				entry.getScope().key() + ":" + entry.getMemoryId()));
			return Future.succeededFuture(mcpResultWithReferences(text.toString(), references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
