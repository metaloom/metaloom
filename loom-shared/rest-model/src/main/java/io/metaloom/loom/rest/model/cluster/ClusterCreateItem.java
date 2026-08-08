package io.metaloom.loom.rest.model.cluster;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.json.JsonObject;

/**
 * One machine-proposed cluster within a bulk write.
 *
 * <p>
 * Deliberately not {@link ClusterCreateRequest}: that one is the human-authored shape and requires a name, which a proposal does not have until
 * somebody reviews it. This one carries the producer's provenance and the geometry of its belief instead.
 * </p>
 */
public class ClusterCreateItem {

	private String type;

	private String nodeKind;

	private String nodeId;

	private String producerVersion;

	private Integer clusterIndex;

	private Float score;

	private Float[] centroid;

	private String model;

	private Integer dimensions;

	private JsonObject meta;

	private List<ClusterMemberCreateItem> members = new ArrayList<>();

	public String getType() {
		return type;
	}

	public ClusterCreateItem setType(String type) {
		this.type = type;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public ClusterCreateItem setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public ClusterCreateItem setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public ClusterCreateItem setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	/**
	 * Deterministic ordinal within the asset; the upsert key.
	 *
	 * <p>
	 * The producer must derive this from the cluster's content, not from the order it happened to compute them in, or a re-run appends a second set
	 * instead of replacing the first.
	 * </p>
	 */
	public Integer getClusterIndex() {
		return clusterIndex;
	}

	public ClusterCreateItem setClusterIndex(Integer clusterIndex) {
		this.clusterIndex = clusterIndex;
		return this;
	}

	/** Cohesion of the cluster; null for a single-member one. */
	public Float getScore() {
		return score;
	}

	public ClusterCreateItem setScore(Float score) {
		this.score = score;
		return this;
	}

	/** Unit-normalised mean of the member vectors. Only meaningful together with {@link #getModel()} and {@link #getDimensions()}. */
	public Float[] getCentroid() {
		return centroid;
	}

	public ClusterCreateItem setCentroid(Float[] centroid) {
		this.centroid = centroid;
		return this;
	}

	public String getModel() {
		return model;
	}

	public ClusterCreateItem setModel(String model) {
		this.model = model;
		return this;
	}

	public Integer getDimensions() {
		return dimensions;
	}

	public ClusterCreateItem setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
		return this;
	}

	public JsonObject getMeta() {
		return meta;
	}

	public ClusterCreateItem setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	public List<ClusterMemberCreateItem> getMembers() {
		return members;
	}

	public ClusterCreateItem setMembers(List<ClusterMemberCreateItem> members) {
		this.members = members;
		return this;
	}

	public ClusterCreateItem add(ClusterMemberCreateItem member) {
		this.members.add(member);
		return this;
	}

}
