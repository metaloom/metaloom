package io.metaloom.loom.client.common.method;

import java.util.UUID;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterConfirmRequest;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterMemberListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;

public interface ClusterMethods {

	LoomClientRequest<ClusterResponse> loadCluster(UUID clusterUuid);

	LoomClientRequest<ClusterResponse> createCluster(ClusterCreateRequest request);

	LoomClientRequest<ClusterResponse> updateCluster(UUID clusterUuid, ClusterUpdateRequest request);

	LoomClientRequest<ClusterListResponse> listClusters();

	/**
	 * List clusters filtered by review status and/or type - the review queue.
	 *
	 * @param status one of PENDING, CONFIRMED, REJECTED, or {@code null} for any
	 * @param type   the cluster type, e.g. {@code face}, or {@code null} for any
	 */
	LoomClientRequest<ClusterListResponse> listClusters(String status, String type);

	LoomClientRequest<NoResponse> deleteCluster(UUID clusterUuid);

	/** The embeddings belonging to a cluster, each with the detection geometry needed to crop the face. */
	LoomClientRequest<ClusterMemberListResponse> listClusterMembers(UUID clusterUuid);

	/** Confirm that a cluster is a person, linking an existing one or creating a new one. */
	LoomClientRequest<ClusterResponse> confirmCluster(UUID clusterUuid, ClusterConfirmRequest request);

	/** Reject a cluster: it is not a subject worth keeping. */
	LoomClientRequest<ClusterResponse> rejectCluster(UUID clusterUuid);

	/** The clusters computed within one asset. */
	LoomClientRequest<ClusterListResponse> listAssetClusters(AssetId assetId);

	default LoomClientRequest<ClusterListResponse> listAssetClusters(UUID assetUuid) {
		return listAssetClusters(assetId(assetUuid));
	}

	/**
	 * Write every cluster a producer found in one asset.
	 *
	 * <p>
	 * Idempotent on {@code (asset, nodeKind, clusterIndex)}: re-running the producing node rewrites its own proposals and never overwrites a review
	 * verdict recorded against them.
	 * </p>
	 */
	LoomClientRequest<ClusterBulkResponse> bulkCreateAssetClusters(AssetId assetId, ClusterBulkCreateRequest request);

	default LoomClientRequest<ClusterBulkResponse> bulkCreateAssetClusters(UUID assetUuid, ClusterBulkCreateRequest request) {
		return bulkCreateAssetClusters(assetId(assetUuid), request);
	}

}
