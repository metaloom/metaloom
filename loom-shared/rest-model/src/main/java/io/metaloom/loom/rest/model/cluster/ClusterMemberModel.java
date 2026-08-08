package io.metaloom.loom.rest.model.cluster;

/**
 * One embedding's membership of a cluster, with the geometry of the detection it came from.
 *
 * <p>
 * The detection fields ride along because every consumer needs them together: a review UI showing the faces in a cluster addresses each crop by
 * {@code detectionUuid} and describes it with the bounding box. Returning the membership alone would cost one extra request per face.
 * </p>
 */
public class ClusterMemberModel {

	private String embeddingUuid;

	private String detectionUuid;

	private String assetUuid;

	private Integer frameNumber;

	private Float bboxX;

	private Float bboxY;

	private Float bboxWidth;

	private Float bboxHeight;

	private Float confidence;

	private String origin;

	public String getEmbeddingUuid() {
		return embeddingUuid;
	}

	public ClusterMemberModel setEmbeddingUuid(String embeddingUuid) {
		this.embeddingUuid = embeddingUuid;
		return this;
	}

	/** The detection this member depicts, or null for an embedding written without one. Addresses the face crop. */
	public String getDetectionUuid() {
		return detectionUuid;
	}

	public ClusterMemberModel setDetectionUuid(String detectionUuid) {
		this.detectionUuid = detectionUuid;
		return this;
	}

	public String getAssetUuid() {
		return assetUuid;
	}

	public ClusterMemberModel setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	/** Frame the detection was made in; 0 for a still image. */
	public Integer getFrameNumber() {
		return frameNumber;
	}

	public ClusterMemberModel setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	/** Bounding box left edge, as a 0-1 factor of the frame width. */
	public Float getBboxX() {
		return bboxX;
	}

	public ClusterMemberModel setBboxX(Float bboxX) {
		this.bboxX = bboxX;
		return this;
	}

	/** Bounding box top edge, as a 0-1 factor of the frame height. */
	public Float getBboxY() {
		return bboxY;
	}

	public ClusterMemberModel setBboxY(Float bboxY) {
		this.bboxY = bboxY;
		return this;
	}

	public Float getBboxWidth() {
		return bboxWidth;
	}

	public ClusterMemberModel setBboxWidth(Float bboxWidth) {
		this.bboxWidth = bboxWidth;
		return this;
	}

	public Float getBboxHeight() {
		return bboxHeight;
	}

	public ClusterMemberModel setBboxHeight(Float bboxHeight) {
		this.bboxHeight = bboxHeight;
		return this;
	}

	/** Cosine similarity of this member to the cluster centroid at assignment time. */
	public Float getConfidence() {
		return confidence;
	}

	public ClusterMemberModel setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	/** AUTO when the clusterer assigned this member, MANUAL when a reviewer moved it. */
	public String getOrigin() {
		return origin;
	}

	public ClusterMemberModel setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

}
