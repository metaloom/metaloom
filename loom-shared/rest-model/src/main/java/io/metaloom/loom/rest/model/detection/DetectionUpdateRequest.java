package io.metaloom.loom.rest.model.detection;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class DetectionUpdateRequest extends AbstractMetaModel<DetectionUpdateRequest>
	implements RestRequestModel, DetectionModel<DetectionUpdateRequest> {

	private String type;

	private Integer frameNumber;

	private Float bboxX;

	private Float bboxY;

	private Float bboxWidth;

	private Float bboxHeight;

	private Float confidence;

	@Override
	public String getType() {
		return type;
	}

	@Override
	public DetectionUpdateRequest setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public Integer getFrameNumber() {
		return frameNumber;
	}

	@Override
	public DetectionUpdateRequest setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	@Override
	public Float getBboxX() {
		return bboxX;
	}

	@Override
	public DetectionUpdateRequest setBboxX(Float bboxX) {
		this.bboxX = bboxX;
		return this;
	}

	@Override
	public Float getBboxY() {
		return bboxY;
	}

	@Override
	public DetectionUpdateRequest setBboxY(Float bboxY) {
		this.bboxY = bboxY;
		return this;
	}

	@Override
	public Float getBboxWidth() {
		return bboxWidth;
	}

	@Override
	public DetectionUpdateRequest setBboxWidth(Float bboxWidth) {
		this.bboxWidth = bboxWidth;
		return this;
	}

	@Override
	public Float getBboxHeight() {
		return bboxHeight;
	}

	@Override
	public DetectionUpdateRequest setBboxHeight(Float bboxHeight) {
		this.bboxHeight = bboxHeight;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public DetectionUpdateRequest setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	@Override
	public DetectionUpdateRequest self() {
		return this;
	}

}
