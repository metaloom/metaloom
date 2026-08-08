package io.metaloom.loom.db.jooq.dao.cluster;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.cluster.Cluster;

public class ClusterImpl extends AbstractEditableElement<Cluster> implements Cluster {

	private String name;

	private String type;

	private String status;

	private UUID personUuid;

	private UUID assetUuid;

	private Integer clusterIndex;

	private Float score;

	private Float[] centroid;

	private String model;

	private Integer dimensions;

	private String nodeKind;

	private String nodeId;

	private String producerVersion;

	private UUID runUuid;

	private UUID taskUuid;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Cluster setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public Cluster setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public String getStatus() {
		return status;
	}

	@Override
	public Cluster setStatus(String status) {
		this.status = status;
		return this;
	}

	@Override
	public UUID getPersonUuid() {
		return personUuid;
	}

	@Override
	public Cluster setPersonUuid(UUID personUuid) {
		this.personUuid = personUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Cluster setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public Integer getClusterIndex() {
		return clusterIndex;
	}

	@Override
	public Cluster setClusterIndex(Integer clusterIndex) {
		this.clusterIndex = clusterIndex;
		return this;
	}

	@Override
	public Float getScore() {
		return score;
	}

	@Override
	public Cluster setScore(Float score) {
		this.score = score;
		return this;
	}

	@Override
	public Float[] getCentroid() {
		return centroid;
	}

	@Override
	public Cluster setCentroid(Float[] centroid) {
		this.centroid = centroid;
		return this;
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public Cluster setModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public Integer getDimensions() {
		return dimensions;
	}

	@Override
	public Cluster setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
		return this;
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public Cluster setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public Cluster setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public Cluster setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public UUID getRunUuid() {
		return runUuid;
	}

	@Override
	public Cluster setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	@Override
	public UUID getTaskUuid() {
		return taskUuid;
	}

	@Override
	public Cluster setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

}
