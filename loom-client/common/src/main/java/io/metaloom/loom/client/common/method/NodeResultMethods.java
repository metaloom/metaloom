package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultListResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

public interface NodeResultMethods {

	/**
	 * Record (upsert) a node processing result for an asset. Keyed by {@code (asset, node_kind, node_id)}; re-posting the same key rewrites the row.
	 */
	LoomClientRequest<NodeResultResponse> createAssetNodeResult(UUID assetUuid, NodeResultCreateRequest request);

	LoomClientRequest<NodeResultResponse> loadAssetNodeResult(UUID assetUuid, UUID nodeResultUuid);

	LoomClientRequest<NodeResultListResponse> listAssetNodeResults(UUID assetUuid);

	LoomClientRequest<NoResponse> deleteAssetNodeResult(UUID assetUuid, UUID nodeResultUuid);

}
