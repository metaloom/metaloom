package io.metaloom.loom.rest.model.noderesult;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class NodeResultListResponse extends AbstractListResponse<NodeResultListResponse, NodeResultResponse> {

	@Override
	public NodeResultListResponse self() {
		return this;
	}

}
