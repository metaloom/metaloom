package io.metaloom.loom.db.jooq.dao.embedding;

import java.time.LocalDateTime;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.embedding.Embedding;

public class EmbeddingImpl extends AbstractEditableElement<Embedding> implements Embedding {

	private String nodeKind;

	private String producerVersion = "";

	private String model;

	private Integer dimensions;

	private Float confidence;

	private UUID detectionUuid;

	private int frameNumber;

	private int subjectIndex;

	private Float[] vector;

	private String type;

	private UUID assetUuid;

	private Boolean dirty;

	private LocalDateTime syncedAt;

	private Integer indexVersion;

	private Boolean normalized;

	public EmbeddingImpl() {
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public Embedding setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public Embedding setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion == null ? "" : producerVersion;
		return this;
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public Embedding setModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public Integer getDimensions() {
		return dimensions;
	}

	@Override
	public Embedding setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
		return this;
	}

	@Override
	public UUID getDetectionUuid() {
		return detectionUuid;
	}

	@Override
	public Embedding setDetectionUuid(UUID detectionUuid) {
		this.detectionUuid = detectionUuid;
		return this;
	}

	@Override
	public int getFrameNumber() {
		return frameNumber;
	}

	@Override
	public Embedding setFrameNumber(int frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	@Override
	public int getSubjectIndex() {
		return subjectIndex;
	}

	@Override
	public Embedding setSubjectIndex(int subjectIndex) {
		this.subjectIndex = subjectIndex;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Embedding setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public Embedding setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public Float[] getVector() {
		return vector;
	}

	@Override
	public Embedding setVector(Float[] vectorData) {
		this.vector = vectorData;
		return this;
	}

	@Override
	public Boolean getDirty() {
		return dirty;
	}

	@Override
	public Embedding setDirty(Boolean dirty) {
		this.dirty = dirty;
		return this;
	}

	@Override
	public LocalDateTime getSyncedAt() {
		return syncedAt;
	}

	@Override
	public Embedding setSyncedAt(LocalDateTime syncedAt) {
		this.syncedAt = syncedAt;
		return this;
	}

	@Override
	public Integer getIndexVersion() {
		return indexVersion;
	}

	@Override
	public Embedding setIndexVersion(Integer indexVersion) {
		this.indexVersion = indexVersion;
		return this;
	}

	@Override
	public Boolean getNormalized() {
		return normalized;
	}

	@Override
	public Embedding setNormalized(Boolean normalized) {
		this.normalized = normalized;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public Embedding setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

}
