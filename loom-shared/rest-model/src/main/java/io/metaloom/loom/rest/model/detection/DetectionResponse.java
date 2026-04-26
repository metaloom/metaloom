package io.metaloom.loom.rest.model.detection;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class DetectionResponse extends AbstractCreatorEditorRestResponse<DetectionResponse>
	implements DetectionModel<DetectionResponse> {

	private String type;

	private Integer frameNumber;

	private Float bboxX;

	private Float bboxY;

	private Float bboxWidth;

	private Float bboxHeight;

	private Float confidence;

	private String assetUuid;

	@Override
	public String getType() {
		return type;
	}

	@Override
	public DetectionResponse setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public Integer getFrameNumber() {
		return frameNumber;
	}

	@Override
	public DetectionResponse setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	@Override
	public Float getBboxX() {
		return bboxX;
	}

	@Override
	public DetectionResponse setBboxX(Float bboxX) {
		this.bboxX = bboxX;
		return this;
	}

	@Override
	public Float getBboxY() {
		return bboxY;
	}

	@Override
	public DetectionResponse setBboxY(Float bboxY) {
		this.bboxY = bboxY;
		return this;
	}

	@Override
	public Float getBboxWidth() {
		return bboxWidth;
	}

	@Override
	public DetectionResponse setBboxWidth(Float bboxWidth) {
		this.bboxWidth = bboxWidth;
		return this;
	}

	@Override
	public Float getBboxHeight() {
		return bboxHeight;
	}

	@Override
	public DetectionResponse setBboxHeight(Float bboxHeight) {
		this.bboxHeight = bboxHeight;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public DetectionResponse setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	public String getAssetUuid() {
		return assetUuid;
	}

	public DetectionResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public DetectionResponse self() {
		return this;
	}

}
