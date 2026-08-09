package io.metaloom.loom.rest.model.noderun;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * Paged list of the caller's own ad-hoc node runs, newest first.
 *
 * <p>
 * Scoped to the creator by construction: an ad-hoc run belongs to whoever started it, and there is no
 * pipeline for anybody else to have been given access to.
 * </p>
 */
public class NodeRunListResponse extends AbstractListResponse<NodeRunListResponse, NodeRunStatusResponse>
	implements RestResponseModel<NodeRunListResponse> {

	public NodeRunListResponse() {
	}

	@Override
	public NodeRunListResponse self() {
		return this;
	}

}
