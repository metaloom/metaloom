package io.metaloom.loom.rest.model.detection;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class DetectionListResponse extends AbstractListResponse<DetectionListResponse, DetectionResponse> {

	@Override
	public DetectionListResponse self() {
		return this;
	}

}
