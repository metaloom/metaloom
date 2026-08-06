package io.metaloom.loom.rest.model.embedding;

import java.util.UUID;

import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class EmbeddingResponse extends AbstractCreatorEditorRestResponse<EmbeddingResponse> implements EmbeddingModel<EmbeddingResponse> {

	private String source;

	private String type;

	private Float[] vector;

	private AreaInfo area;

	private UUID assetUuid;

	private String model;

	private Integer dimensions;

	private UUID detectionUuid;

	private Integer frameNumber;

	private Integer subjectIndex;

	private Boolean normalized;

	/**
	 * Return the readable model identifier this vector was produced by, e.g. inspireface-r18. Part of the embedding identity, so two models can coexist
	 * for one asset and a caller can tell them apart.
	 */
	public String getModel() {
		return model;
	}

	public EmbeddingResponse setModel(String model) {
		this.model = model;
		return this;
	}

	public Integer getDimensions() {
		return dimensions;
	}

	public EmbeddingResponse setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
		return this;
	}

	/**
	 * Return the detection this vector was computed from, or null for a whole-image or audio-window embedding.
	 */
	public UUID getDetectionUuid() {
		return detectionUuid;
	}

	public EmbeddingResponse setDetectionUuid(UUID detectionUuid) {
		this.detectionUuid = detectionUuid;
		return this;
	}

	public Integer getFrameNumber() {
		return frameNumber;
	}

	public EmbeddingResponse setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	public Integer getSubjectIndex() {
		return subjectIndex;
	}

	public EmbeddingResponse setSubjectIndex(Integer subjectIndex) {
		this.subjectIndex = subjectIndex;
		return this;
	}

	public Boolean getNormalized() {
		return normalized;
	}

	public EmbeddingResponse setNormalized(Boolean normalized) {
		this.normalized = normalized;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public EmbeddingResponse setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public EmbeddingResponse setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public EmbeddingResponse setVector(Float[] vector) {
		this.vector = vector;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public EmbeddingResponse setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public EmbeddingResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public EmbeddingResponse self() {
		return this;
	}

}
