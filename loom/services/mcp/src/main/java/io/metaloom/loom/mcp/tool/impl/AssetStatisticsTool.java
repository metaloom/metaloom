package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: asset_statistics
 *
 * <p>Returns aggregate statistics about the asset library: total count,
 * breakdown by MIME type, total storage, etc.</p>
 */
@Singleton
public class AssetStatisticsTool implements MCPTool {

	private final DaoCollection daos;

	@Inject
	public AssetStatisticsTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"asset_statistics",
			"Get aggregate statistics about the asset library: total count, storage used, MIME type distribution, and more. Useful for understanding what content is available.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("collection", "string", "Optional collection UUID to scope statistics", false)
			)),
			List.of("READ_ASSET")
		);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			AssetDao assetDao = daos.assetDao();

			// Load a large page to compute stats. In production this should use
			// SQL aggregate queries directly.
			Page<Asset> page = assetDao.loadPage(null, 10000, null, null, null);
			List<Asset> assets = new java.util.ArrayList<>();
			for (Asset a : page) {
				assets.add(a);
			}

			long totalSize = 0;
			int imageCount = 0;
			int videoCount = 0;
			int audioCount = 0;
			int documentCount = 0;
			int otherCount = 0;

			for (Asset asset : assets) {
				totalSize += asset.getSize();
				String mime = asset.getMimeType();
				if (mime != null) {
					if (mime.startsWith("image/")) {
						imageCount++;
					} else if (mime.startsWith("video/")) {
						videoCount++;
					} else if (mime.startsWith("audio/")) {
						audioCount++;
					} else if (mime.startsWith("application/pdf") || mime.startsWith("text/")) {
						documentCount++;
					} else {
						otherCount++;
					}
				}
			}

			JsonObject stats = new JsonObject()
				.put("totalAssets", assets.size())
				.put("totalStorageBytes", totalSize)
				.put("totalStorageMB", totalSize / (1024 * 1024))
				.put("images", imageCount)
				.put("videos", videoCount)
				.put("audio", audioCount)
				.put("documents", documentCount)
				.put("other", otherCount);

			return Future.succeededFuture(mcpTextResult("Asset Statistics:\n" + stats.encodePrettily()));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
