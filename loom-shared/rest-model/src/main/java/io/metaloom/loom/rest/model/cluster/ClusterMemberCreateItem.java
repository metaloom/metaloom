package io.metaloom.loom.rest.model.cluster;

/**
 * One embedding to place in a cluster being created.
 */
public class ClusterMemberCreateItem {

	private String embeddingUuid;

	private Float confidence;

	private String origin;

	public String getEmbeddingUuid() {
		return embeddingUuid;
	}

	public ClusterMemberCreateItem setEmbeddingUuid(String embeddingUuid) {
		this.embeddingUuid = embeddingUuid;
		return this;
	}

	/** Cosine similarity of this member to the cluster centroid. */
	public Float getConfidence() {
		return confidence;
	}

	public ClusterMemberCreateItem setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	/** AUTO (the default) or MANUAL. */
	public String getOrigin() {
		return origin;
	}

	public ClusterMemberCreateItem setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

}
