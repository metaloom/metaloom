package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class ClusterResponse extends AbstractCreatorEditorRestResponse<ClusterResponse> implements ClusterModel<ClusterResponse> {

	private String name;

	private String type;

	private String reviewStatus;

	private String personUuid;

	private String assetUuid;

	private Integer clusterIndex;

	private Float score;

	private Long memberCount;

	private String nodeKind;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ClusterResponse setName(String name) {
		this.name = name;
		return this;
	}

	public String getType() {
		return type;
	}

	public ClusterResponse setType(String type) {
		this.type = type;
		return this;
	}

	/**
	 * Review state: PENDING, CONFIRMED or REJECTED.
	 *
	 * <p>
	 * Named {@code reviewStatus} rather than {@code status} because {@link AbstractCreatorEditorRestResponse} already publishes a {@code status} object
	 * carrying the creator/editor audit block. The two are unrelated, and one of them had to give way; the review verdict is the one that reads better
	 * qualified.
	 * </p>
	 */
	public String getReviewStatus() {
		return reviewStatus;
	}

	public ClusterResponse setReviewStatus(String reviewStatus) {
		this.reviewStatus = reviewStatus;
		return this;
	}

	/** The person this cluster was confirmed to be, or null while it is unreviewed or rejected. */
	public String getPersonUuid() {
		return personUuid;
	}

	public ClusterResponse setPersonUuid(String personUuid) {
		this.personUuid = personUuid;
		return this;
	}

	/** The asset the cluster was computed within, or null for a human-authored cluster. */
	public String getAssetUuid() {
		return assetUuid;
	}

	public ClusterResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	/** Ordinal of this cluster within its asset, stable across re-runs of the producing node. */
	public Integer getClusterIndex() {
		return clusterIndex;
	}

	public ClusterResponse setClusterIndex(Integer clusterIndex) {
		this.clusterIndex = clusterIndex;
		return this;
	}

	/** Cohesion of the cluster, or null for a single-member one, which has nothing to cohere with. */
	public Float getScore() {
		return score;
	}

	public ClusterResponse setScore(Float score) {
		this.score = score;
		return this;
	}

	/**
	 * How many embeddings belong to this cluster.
	 *
	 * <p>
	 * Carried on the cluster so a review queue can render "N faces" on every card of a page without one member query per card.
	 * </p>
	 */
	public Long getMemberCount() {
		return memberCount;
	}

	public ClusterResponse setMemberCount(Long memberCount) {
		this.memberCount = memberCount;
		return this;
	}

	/** The node kind that proposed this cluster, or null when a user authored it. */
	public String getNodeKind() {
		return nodeKind;
	}

	public ClusterResponse setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public ClusterResponse self() {
		return this;
	}

}
