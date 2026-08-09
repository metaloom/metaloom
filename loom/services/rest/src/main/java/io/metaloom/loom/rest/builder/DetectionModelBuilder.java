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
		// Written since the column existed, readable only now. An object detection whose class does not
		// survive the round trip is findable by geometry alone, which is not what anyone asks a detector.
		response.setLabel(detection.getLabel());
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
		response.setReviewStatus(detection.getStatus());
		response.setReviewedAt(detection.getReviewedAt() == null ? null : detection.getReviewedAt().toString());
		response.setCorrectedLabel(detection.getCorrectedLabel());
		// setStatus writes the creator/editor audit envelope, which is a different "status" entirely - see DetectionResponse#getReviewStatus.
		setStatus(detection, response);
		return response;
	}

	default DetectionListResponse toDetectionList(Page<Detection> page) {
		return setPage(new DetectionListResponse(), page, this::toResponse);
	}

}
