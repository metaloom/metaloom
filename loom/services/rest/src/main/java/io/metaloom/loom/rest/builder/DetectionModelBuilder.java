package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;

public interface DetectionModelBuilder extends ModelBuilder, UserModelBuilder {

	default DetectionResponse toResponse(Detection detection) {
		DetectionResponse response = new DetectionResponse();
		response.setUuid(detection.getUuid());
		response.setType(detection.getType());
		response.setFrameNumber(detection.getFrameNumber());
		response.setBboxX(detection.getBboxX());
		response.setBboxY(detection.getBboxY());
		response.setBboxWidth(detection.getBboxWidth());
		response.setBboxHeight(detection.getBboxHeight());
		response.setConfidence(detection.getConfidence());
		response.setMeta(detection.getMeta());
		if (detection.getAssetUuid() != null) {
			response.setAssetUuid(detection.getAssetUuid().toString());
		}
		setStatus(detection, response);
		return response;
	}

	default DetectionListResponse toDetectionList(Page<Detection> page) {
		return setPage(new DetectionListResponse(), page, this::toResponse);
	}

}
