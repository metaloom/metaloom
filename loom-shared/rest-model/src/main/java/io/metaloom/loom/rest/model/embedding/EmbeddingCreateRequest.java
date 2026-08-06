package io.metaloom.loom.rest.model.embedding;

import java.util.UUID;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class EmbeddingCreateRequest extends AbstractMetaModel<EmbeddingCreateRequest>
	implements EmbeddingModel<EmbeddingCreateRequest>, RestRequestModel {

	private String source;

	private AreaInfo area;

	private String type;

	private Float[] vector;

	private UUID assetUuid;

	private String nodeKind;

	private String model;

	private Integer dimensions;

	private UUID detectionUuid;

	private Integer frameNumber;

	private Integer subjectIndex;

	private Float confidence;

	private String producerVersion;

	private Boolean normalized;

	public String getSource() {
		return source;
	}

	public EmbeddingCreateRequest setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public EmbeddingCreateRequest setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	public String getType() {
		return type;
	}

	public EmbeddingCreateRequest setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public EmbeddingCreateRequest setVector(Float[] vector) {
		this.vector = vector;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public EmbeddingCreateRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	/**
	 * Return the kind of node that produced this vector, e.g. "facedetect". Part of the embedding identity, so a node that runs again rewrites its own
	 * rows rather than appending duplicates. Defaults to "manual" when a user creates the embedding through the API.
	 */
	public String getNodeKind() {
		return nodeKind;
	}

	public EmbeddingCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	/**
	 * Return the readable model identifier, e.g. inspireface-r18. Part of the identity too: raising the model adds rows beside the old ones instead of
	 * overwriting them, so two models can be compared before the older one is dropped.
	 */
	public String getModel() {
		return model;
	}

	public EmbeddingCreateRequest setModel(String model) {
		this.model = model;
		return this;
	}

	/**
	 * Return the vector length. Left null it is derived from the vector; supplied, it must agree with it or the write is rejected.
	 */
	public Integer getDimensions() {
		return dimensions;
	}

	public EmbeddingCreateRequest setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
		return this;
	}

	/**
	 * Return the detection this vector was computed from. Null for whole-image and audio-window embeddings, which have no region to point at.
	 */
	public UUID getDetectionUuid() {
		return detectionUuid;
	}

	public EmbeddingCreateRequest setDetectionUuid(UUID detectionUuid) {
		this.detectionUuid = detectionUuid;
		return this;
	}

	/**
	 * Return the frame this embedding belongs to; 0 for images.
	 */
	public Integer getFrameNumber() {
		return frameNumber;
	}

	public EmbeddingCreateRequest setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	/**
	 * Return the ordinal of the subject within the frame. This is what separates two faces found in the same frame by the same node.
	 */
	public Integer getSubjectIndex() {
		return subjectIndex;
	}

	public EmbeddingCreateRequest setSubjectIndex(Integer subjectIndex) {
		this.subjectIndex = subjectIndex;
		return this;
	}

	public Float getConfidence() {
		return confidence;
	}

	public EmbeddingCreateRequest setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public EmbeddingCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	/**
	 * Return whether the vector was unit-normalized by the producer. Normalized vectors rank identically under cosine and inner product, so recording it
	 * keeps that assumption auditable rather than implicit.
	 */
	public Boolean getNormalized() {
		return normalized;
	}

	public EmbeddingCreateRequest setNormalized(Boolean normalized) {
		this.normalized = normalized;
		return this;
	}

	@Override
	public EmbeddingCreateRequest self() {
		return this;
	}

}
