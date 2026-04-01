package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;

public interface ClusterModelBuilder extends ModelBuilder {

	default ClusterResponse toResponse(Cluster cluster) {
		ClusterResponse response = new ClusterResponse();
		response.setUuid(cluster.getUuid());
		response.setName(cluster.getName());
		return response;
	}

	default ClusterListResponse toClusterList(Page<Cluster> page) {
		return setPage(new ClusterListResponse(), page, this::toResponse);
	}

}
