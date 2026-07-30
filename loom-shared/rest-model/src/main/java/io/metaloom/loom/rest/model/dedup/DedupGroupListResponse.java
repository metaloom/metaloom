package io.metaloom.loom.rest.model.dedup;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * List of dedup review groups.
 */
public class DedupGroupListResponse extends AbstractListResponse<DedupGroupListResponse, DedupGroupResponse> {

	@Override
	public DedupGroupListResponse self() {
		return this;
	}
}
