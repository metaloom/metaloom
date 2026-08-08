package io.metaloom.loom.db.jooq.dao.cluster;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.model.cluster.ClusterMember;

public class ClusterMemberImpl implements ClusterMember {

	private UUID clusterUuid;

	private UUID embeddingUuid;

	private Float confidence;

	private String origin;

	private Instant created;

	private UUID detectionUuid;

	private UUID assetUuid;

	private Integer frameNumber;

	private Float bboxX;

	private Float bboxY;

	private Float bboxWidth;

	private Float bboxHeight;

	@Override
	public UUID getClusterUuid() {
		return clusterUuid;
	}

	@Override
	public ClusterMember setClusterUuid(UUID clusterUuid) {
		this.clusterUuid = clusterUuid;
		return this;
	}

	@Override
	public UUID getEmbeddingUuid() {
		return embeddingUuid;
	}

	@Override
	public ClusterMember setEmbeddingUuid(UUID embeddingUuid) {
		this.embeddingUuid = embeddingUuid;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public ClusterMember setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	@Override
	public String getOrigin() {
		return origin;
	}

	@Override
	public ClusterMember setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public ClusterMember setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public UUID getDetectionUuid() {
		return detectionUuid;
	}

	@Override
	public ClusterMember setDetectionUuid(UUID detectionUuid) {
		this.detectionUuid = detectionUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public ClusterMember setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public Integer getFrameNumber() {
		return frameNumber;
	}

	@Override
	public ClusterMember setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	@Override
	public Float getBboxX() {
		return bboxX;
	}

	@Override
	public ClusterMember setBboxX(Float bboxX) {
		this.bboxX = bboxX;
		return this;
	}

	@Override
	public Float getBboxY() {
		return bboxY;
	}

	@Override
	public ClusterMember setBboxY(Float bboxY) {
		this.bboxY = bboxY;
		return this;
	}

	@Override
	public Float getBboxWidth() {
		return bboxWidth;
	}

	@Override
	public ClusterMember setBboxWidth(Float bboxWidth) {
		this.bboxWidth = bboxWidth;
		return this;
	}

	@Override
	public Float getBboxHeight() {
		return bboxHeight;
	}

	@Override
	public ClusterMember setBboxHeight(Float bboxHeight) {
		this.bboxHeight = bboxHeight;
		return this;
	}

}
