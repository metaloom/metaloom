package io.metaloom.loom.rest.model.detection;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class DetectionCreateRequest extends AbstractMetaModel<DetectionCreateRequest>
	implements RestRequestModel, DetectionModel<DetectionCreateRequest> {

	private String type;

	private String nodeKind;

	private String producerVersion;

	private String label;

	private Integer detectionIndex;

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
	public DetectionCreateRequest setType(String type) {
		this.type = type;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public DetectionCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public DetectionCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public String getLabel() {
		return label;
	}

	public DetectionCreateRequest setLabel(String label) {
		this.label = label;
		return this;
	}

	public Integer getDetectionIndex() {
		return detectionIndex;
	}

	public DetectionCreateRequest setDetectionIndex(Integer detectionIndex) {
		this.detectionIndex = detectionIndex;
		return this;
	}

	@Override
	public Integer getFrameNumber() {
		return frameNumber;
	}

	@Override
	public DetectionCreateRequest setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	@Override
	public Float getBboxX() {
		return bboxX;
	}

	@Override
	public DetectionCreateRequest setBboxX(Float bboxX) {
		this.bboxX = bboxX;
		return this;
	}

	@Override
	public Float getBboxY() {
		return bboxY;
	}

	@Override
	public DetectionCreateRequest setBboxY(Float bboxY) {
		this.bboxY = bboxY;
		return this;
	}

	@Override
	public Float getBboxWidth() {
		return bboxWidth;
	}

	@Override
	public DetectionCreateRequest setBboxWidth(Float bboxWidth) {
		this.bboxWidth = bboxWidth;
		return this;
	}

	@Override
	public Float getBboxHeight() {
		return bboxHeight;
	}

	@Override
	public DetectionCreateRequest setBboxHeight(Float bboxHeight) {
		this.bboxHeight = bboxHeight;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public DetectionCreateRequest setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	@Override
	public DetectionCreateRequest self() {
		return this;
	}

}
