package io.metaloom.loom.db.model.asset;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.Dao;
import io.vertx.core.json.JsonObject;

/**
 * The node output reuse store (V2.116).
 *
 * <p>
 * Not a {@code CRUDDao}: an output is never authored, only recorded by the node that produced it. A later write for the same
 * {@code (asset, node kind, reuse key)} replaces it; deleting the asset deletes it.
 * </p>
 */
public interface AssetNodeOutputDao extends Dao {

	/**
	 * Store what a node emitted, replacing an earlier row for the same {@code (asset, node kind, reuse key)}.
	 *
	 * @param state   {@link AssetNodeResult#STATE_SUCCESS} or {@link AssetNodeResult#STATE_SKIPPED}
	 * @param runUuid the run that produced it, or null
	 */
	AssetNodeOutput upsert(UUID assetUuid, String nodeKind, String reuseKey, String nodeId, String producerVersion, String state, String reason,
		JsonObject outputs, UUID runUuid);

	/**
	 * The stored output for one node configuration against one asset, or null when there is none - the lookup every reusing node makes.
	 */
	AssetNodeOutput loadByKey(UUID assetUuid, String nodeKind, String reuseKey);

	/**
	 * Every stored output for an asset, oldest first.
	 *
	 * @param nodeKind restrict to one kind, or null for all
	 */
	List<AssetNodeOutput> listByAsset(UUID assetUuid, String nodeKind);

	/**
	 * Forget stored outputs for an asset, so the next run of those nodes computes again.
	 *
	 * @param nodeKind restrict to one kind, or null for all
	 * @return the number of rows removed
	 */
	int deleteByAsset(UUID assetUuid, String nodeKind);

}
