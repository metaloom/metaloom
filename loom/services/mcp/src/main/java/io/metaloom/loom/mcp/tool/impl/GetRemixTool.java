package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResultWithReferences;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixMember;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: get_remix
 *
 * <p>
 * Load one remix and the assets in it. Requires {@code READ_ASSET} as well as {@code READ_REMIX},
 * because the member list carries filenames and hashes - the same rule the REST route enforces, so
 * the tool cannot be a side channel around asset visibility.
 * </p>
 */
@Singleton
public class GetRemixTool implements MCPTool {

	private final DaoCollection daos;

	@Inject
	public GetRemixTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"get_remix",
			"Load one remix and the assets it holds. Each member carries its role - SOURCE is the original the remix is built around, "
				+ "DERIVED is something made from it - along with the filename, mime type and size.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("remixUuid", "string", "Uuid of the remix to load", true)
			)),
			List.of("READ_REMIX", "READ_ASSET")
		);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String remixUuidStr = arguments.getString("remixUuid");
			if (remixUuidStr == null || remixUuidStr.isBlank()) {
				return Future.failedFuture("Parameter 'remixUuid' is required");
			}

			UUID remixUuid;
			try {
				remixUuid = UUID.fromString(remixUuidStr);
			} catch (IllegalArgumentException e) {
				return Future.succeededFuture(mcpTextResult("Not a uuid: " + remixUuidStr));
			}

			Remix remix = daos.remixDao().load(remixUuid);
			if (remix == null) {
				return Future.succeededFuture(mcpTextResult("Remix not found: " + remixUuidStr));
			}

			Page<RemixMember> members = daos.remixDao().loadMembers(remixUuid, null, 100);

			JsonArray memberJson = new JsonArray();
			JsonArray references = new JsonArray();
			references.add(reference("remix", remix.getUuid().toString(), remix.getName()));
			for (RemixMember member : members) {
				memberJson.add(new JsonObject()
					.put("assetUuid", member.getAssetUuid().toString())
					.put("role", member.getRole() == null ? null : member.getRole().name())
					.put("filename", member.getFilename())
					.put("mimeType", member.getMimeType())
					.put("size", member.getSize()));
				references.add(reference("asset", member.getAssetUuid().toString(), member.getFilename()));
			}

			JsonObject info = new JsonObject()
				.put("uuid", remix.getUuid().toString())
				.put("name", remix.getName())
				.put("description", remix.getDescription())
				.put("sourceAssetUuid", remix.getSourceAssetUuid() == null ? null : remix.getSourceAssetUuid().toString())
				.put("memberCount", members.totalCount())
				.put("members", memberJson);

			return Future.succeededFuture(mcpResultWithReferences(info.encodePrettily(), references));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
