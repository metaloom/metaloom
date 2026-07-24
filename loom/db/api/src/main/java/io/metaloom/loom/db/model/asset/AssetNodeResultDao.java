package io.metaloom.loom.db.model.asset;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.Dao;

/**
 * DAO for the per-asset processing ledger.
 *
 * <p>
 * The ledger answers the three questions the node system cannot answer from the payload tables alone:
 * </p>
 * <ul>
 * <li>has this node already run on this asset - {@link #loadByNode(UUID, String, String)}</li>
 * <li>what has run on this asset at all - {@link #loadByAsset(UUID)}</li>
 * <li>which assets carry results from a superseded producer version - {@link #findStale(String, String)}</li>
 * </ul>
 */
public interface AssetNodeResultDao extends Dao {

	/**
	 * Create a transient ledger entry. The entry is not persisted until it is passed to {@link #upsert(AssetNodeResult)}.
	 *
	 * @param userUuid  user to record as creator/editor, or null when written by a worker
	 * @param assetUuid the asset the node ran against
	 * @param nodeKind  the node kind, e.g. whisper
	 * @param nodeId    the graph-local node id, or null when the node ran outside a pipeline
	 */
	AssetNodeResult createNodeResult(UUID userUuid, UUID assetUuid, String nodeKind, String nodeId);

	/**
	 * Insert or update the entry keyed by (asset, node kind, node id). Re-running a node rewrites its single ledger row.
	 */
	AssetNodeResult upsert(AssetNodeResult result);

	AssetNodeResult load(UUID uuid);

	/**
	 * Load the ledger entry for one node against one asset, or null when the node has never run against it.
	 */
	AssetNodeResult loadByNode(UUID assetUuid, String nodeKind, String nodeId);

	/**
	 * Load everything that has run against an asset.
	 */
	List<AssetNodeResult> loadByAsset(UUID assetUuid);

	/**
	 * Find ledger entries produced by a version of a node other than the given one - the invalidation sweep after a model upgrade.
	 *
	 * @param nodeKind       the node kind to sweep
	 * @param currentVersion the version considered current
	 */
	List<AssetNodeResult> findStale(String nodeKind, String currentVersion);

	void delete(UUID uuid);
}
