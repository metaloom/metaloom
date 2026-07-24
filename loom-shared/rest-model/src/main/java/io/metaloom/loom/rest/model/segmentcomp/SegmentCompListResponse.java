package io.metaloom.loom.rest.model.segmentcomp;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class SegmentCompListResponse extends AbstractListResponse<SegmentCompListResponse, SegmentCompResponse> {

	@Override
	public SegmentCompListResponse self() {
		return this;
	}

}
