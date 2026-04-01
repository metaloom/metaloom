package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ClusterListResponse extends AbstractListResponse<ClusterListResponse, ClusterResponse> {

	@Override
	public ClusterListResponse self() {
		return this;
	}

}
