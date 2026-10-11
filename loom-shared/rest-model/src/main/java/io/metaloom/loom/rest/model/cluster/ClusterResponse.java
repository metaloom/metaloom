package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class ClusterResponse extends AbstractCreatorEditorRestResponse<ClusterResponse> implements ClusterModel<ClusterResponse> {

	private String name;

	private String type;

	private String reviewStatus;

	private String personUuid;

	/** The name the confirmed person is shown by: alias, else first and last name. Read-only. */
	private String personName;

	/** The person the producing node recognised this cluster as. A proposal only - personUuid stays the reviewer's verdict. */
	private String suggestedPersonUuid;

	/** The name the suggested person is shown by. Read-only. */
	private String suggestedPersonName;

	/** Cosine similarity of the cluster centroid to the suggested person's reference. */
	private Float suggestedScore;

	/** The model the cluster's centroid was computed with. */
	private String model;

	private String reviewedAt;

	private String reviewerUuid;

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

	/**
	 * When a human recorded the verdict, or null while nobody has.
	 *
	 * <p>
	 * Distinct from the {@code edited} timestamp inside the audit block: the producing node touches that on every re-run, so it says nothing about
	 * whether anybody reviewed the cluster.
	 * </p>
	 */
	public String getReviewedAt() {
		return reviewedAt;
	}

	public ClusterResponse setReviewedAt(String reviewedAt) {
		this.reviewedAt = reviewedAt;
		return this;
	}

	/**
	 * The user who recorded the verdict, or null while nobody has.
	 *
	 * <p>
	 * Distinct from the {@code editor} inside the audit block, which is the producing node's provenance and is absent for a node-written cluster.
	 * Attributing a face to a named person is a biometric decision and keeps its author across every later run of that node.
	 * </p>
	 */
	public String getReviewerUuid() {
		return reviewerUuid;
	}

	public ClusterResponse setReviewerUuid(String reviewerUuid) {
		this.reviewerUuid = reviewerUuid;
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

	public String getPersonName() {
		return personName;
	}

	public ClusterResponse setPersonName(String personName) {
		this.personName = personName;
		return this;
	}

	public String getSuggestedPersonUuid() {
		return suggestedPersonUuid;
	}

	public ClusterResponse setSuggestedPersonUuid(String suggestedPersonUuid) {
		this.suggestedPersonUuid = suggestedPersonUuid;
		return this;
	}

	public String getSuggestedPersonName() {
		return suggestedPersonName;
	}

	public ClusterResponse setSuggestedPersonName(String suggestedPersonName) {
		this.suggestedPersonName = suggestedPersonName;
		return this;
	}

	public Float getSuggestedScore() {
		return suggestedScore;
	}

	public ClusterResponse setSuggestedScore(Float suggestedScore) {
		this.suggestedScore = suggestedScore;
		return this;
	}

	public String getModel() {
		return model;
	}

	public ClusterResponse setModel(String model) {
		this.model = model;
		return this;
	}
}
