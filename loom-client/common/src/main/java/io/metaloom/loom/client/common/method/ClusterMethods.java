package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;

public interface ClusterMethods {

	LoomClientRequest<ClusterResponse> loadCluster(UUID clusterUuid);

	LoomClientRequest<ClusterResponse> createCluster(ClusterCreateRequest request);

	LoomClientRequest<ClusterResponse> updateCluster(UUID clusterUuid, ClusterUpdateRequest request);
	
	LoomClientRequest<ClusterListResponse> listClusters();

	LoomClientRequest<NoResponse> deleteCluster(UUID clusterUuid);

}
