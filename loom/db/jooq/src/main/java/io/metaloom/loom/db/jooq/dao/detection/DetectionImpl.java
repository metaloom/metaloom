package io.metaloom.loom.db.jooq.dao.detection;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.detection.Detection;
import io.vertx.core.json.JsonObject;

public class DetectionImpl extends AbstractEditableElement<Detection> implements Detection {

	private String nodeKind;

	private String producerVersion = "";

	private Integer detectionIndex = 0;

	private String label;

	private Long timeFrom;

	private String type;

	private Integer frameNumber;

	private Float bboxX;

	private Float bboxY;

	private Float bboxWidth;

	private Float bboxHeight;

	private Float confidence;

	private UUID assetUuid;

	private JsonObject meta;

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public Detection setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public Detection setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion == null ? "" : producerVersion;
		return this;
	}

	@Override
	public Integer getDetectionIndex() {
		return detectionIndex;
	}

	@Override
	public Detection setDetectionIndex(Integer detectionIndex) {
		this.detectionIndex = detectionIndex;
		return this;
	}

	@Override
	public String getLabel() {
		return label;
	}

	@Override
	public Detection setLabel(String label) {
		this.label = label;
		return this;
	}

	@Override
	public Long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public Detection setTimeFrom(Long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public Detection setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public Integer getFrameNumber() {
		return frameNumber;
	}

	@Override
	public Detection setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	@Override
	public Float getBboxX() {
		return bboxX;
	}

	@Override
	public Detection setBboxX(Float bboxX) {
		this.bboxX = bboxX;
		return this;
	}

	@Override
	public Float getBboxY() {
		return bboxY;
	}

	@Override
	public Detection setBboxY(Float bboxY) {
		this.bboxY = bboxY;
		return this;
	}

	@Override
	public Float getBboxWidth() {
		return bboxWidth;
	}

	@Override
	public Detection setBboxWidth(Float bboxWidth) {
		this.bboxWidth = bboxWidth;
		return this;
	}

	@Override
	public Float getBboxHeight() {
		return bboxHeight;
	}

	@Override
	public Detection setBboxHeight(Float bboxHeight) {
		this.bboxHeight = bboxHeight;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public Detection setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Detection setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public Detection setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
