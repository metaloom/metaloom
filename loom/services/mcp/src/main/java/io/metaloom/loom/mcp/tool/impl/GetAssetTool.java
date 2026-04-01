package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.impl.SearchAssetsTool.mcpTextResult;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: get_asset
 *
 * <p>Load full details for a single asset by UUID or SHA-512 hash.</p>
 */
@Singleton
public class GetAssetTool implements MCPTool {

	private final DaoCollection daos;

	@Inject
	public GetAssetTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"get_asset",
			"Load complete metadata for a single asset, including file info, hashes, media properties, geo location, and components. Accepts either a UUID or SHA-512 hash.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("assetId", "string", "Asset UUID or SHA-512 hash", true)
			))
		);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String assetIdStr = arguments.getString("assetId");
			if (assetIdStr == null || assetIdStr.isBlank()) {
				return Future.failedFuture("Parameter 'assetId' is required");
			}

			AssetDao assetDao = daos.assetDao();
			Asset asset = assetDao.loadById(AssetId.assetId(assetIdStr));

			if (asset == null) {
				return Future.succeededFuture(mcpTextResult("Asset not found: " + assetIdStr));
			}

			JsonObject info = new JsonObject()
				.put("uuid", asset.getUuid().toString())
				.put("filename", asset.getFilename())
				.put("mimeType", asset.getMimeType())
				.put("size", asset.getSize())
				.put("sha512", asset.getSHA512() != null ? asset.getSHA512().toString() : null)
				.put("initialOrigin", asset.getInitialOrigin())
				.put("firstSeen", asset.getFirstSeen() != null ? asset.getFirstSeen().toString() : null)
				.put("s3Bucket", asset.getS3BucketName())
				.put("s3ObjectPath", asset.getS3ObjectPath());

			return Future.succeededFuture(mcpTextResult(info.encodePrettily()));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
