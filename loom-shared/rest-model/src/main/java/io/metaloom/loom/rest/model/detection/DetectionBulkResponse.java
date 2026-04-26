package io.metaloom.loom.rest.model.detection;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestResponseModel;

public class DetectionBulkResponse implements RestResponseModel<DetectionBulkResponse> {

	private List<DetectionResponse> detections = new ArrayList<>();

	private int total;

	private int created;

	private int failed;

	public List<DetectionResponse> getDetections() {
		return detections;
	}

	public DetectionBulkResponse setDetections(List<DetectionResponse> detections) {
		this.detections = detections;
		return this;
	}

	public DetectionBulkResponse add(DetectionResponse response) {
		this.detections.add(response);
		return this;
	}

	public int getTotal() {
		return total;
	}

	public DetectionBulkResponse setTotal(int total) {
		this.total = total;
		return this;
	}

	public int getCreated() {
		return created;
	}

	public DetectionBulkResponse setCreated(int created) {
		this.created = created;
		return this;
	}

	public int getFailed() {
		return failed;
	}

	public DetectionBulkResponse setFailed(int failed) {
		this.failed = failed;
		return this;
	}

	@Override
	public DetectionBulkResponse self() {
		return this;
	}

}
