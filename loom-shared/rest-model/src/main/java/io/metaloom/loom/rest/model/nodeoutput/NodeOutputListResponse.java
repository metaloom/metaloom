package io.metaloom.loom.rest.model.nodeoutput;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class NodeOutputListResponse extends AbstractListResponse<NodeOutputListResponse, NodeOutputResponse> {

	@Override
	public NodeOutputListResponse self() {
		return this;
	}

}
