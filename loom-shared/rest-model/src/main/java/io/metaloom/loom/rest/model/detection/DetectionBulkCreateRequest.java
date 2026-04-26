package io.metaloom.loom.rest.model.detection;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;

public class DetectionBulkCreateRequest implements RestRequestModel {

	private List<DetectionCreateRequest> detections = new ArrayList<>();

	public List<DetectionCreateRequest> getDetections() {
		return detections;
	}

	public DetectionBulkCreateRequest setDetections(List<DetectionCreateRequest> detections) {
		this.detections = detections;
		return this;
	}

}
