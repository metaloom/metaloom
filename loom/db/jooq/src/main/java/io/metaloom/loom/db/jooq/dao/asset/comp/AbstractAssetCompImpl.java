package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetComponent;

/**
 * Shared state of every asset component: the owning asset plus the provenance of the node that produced the component.
 */
public abstract class AbstractAssetCompImpl<SELF extends AssetComponent<SELF>> extends AbstractEditableElement<SELF> implements AssetComponent<SELF> {

	private UUID assetUuid;
	private String nodeKind;
	private String nodeId;
	private String producerVersion = "";
	private UUID runUuid;
	private UUID taskUuid;
	private Float confidence;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public SELF setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return self();
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public SELF setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return self();
	}

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public SELF setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return self();
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public SELF setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion == null ? "" : producerVersion;
		return self();
	}

	@Override
	public UUID getRunUuid() {
		return runUuid;
	}

	@Override
	public SELF setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return self();
	}

	@Override
	public UUID getTaskUuid() {
		return taskUuid;
	}

	@Override
	public SELF setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return self();
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public SELF setConfidence(Float confidence) {
		this.confidence = confidence;
		return self();
	}
}
