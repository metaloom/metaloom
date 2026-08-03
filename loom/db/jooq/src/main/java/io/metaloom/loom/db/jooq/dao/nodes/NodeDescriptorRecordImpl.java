package io.metaloom.loom.db.jooq.dao.nodes;

import java.time.Instant;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecord;

public class NodeDescriptorRecordImpl extends AbstractEditableElement<NodeDescriptorRecord> implements NodeDescriptorRecord {

	private String nodeId;
	private String version;
	private String descriptor;
	private String bodyHash;
	private String source;
	private String status;
	private Instant firstSeen;
	private Instant lastAnnounced;

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public NodeDescriptorRecord setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	@Override
	public String getVersion() {
		return version;
	}

	@Override
	public NodeDescriptorRecord setVersion(String version) {
		this.version = version;
		return this;
	}

	@Override
	public String getDescriptor() {
		return descriptor;
	}

	@Override
	public NodeDescriptorRecord setDescriptor(String descriptor) {
		this.descriptor = descriptor;
		return this;
	}

	@Override
	public String getBodyHash() {
		return bodyHash;
	}

	@Override
	public NodeDescriptorRecord setBodyHash(String bodyHash) {
		this.bodyHash = bodyHash;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public NodeDescriptorRecord setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getStatus() {
		return status;
	}

	@Override
	public NodeDescriptorRecord setStatus(String status) {
		this.status = status;
		return this;
	}

	@Override
	public Instant getFirstSeen() {
		return firstSeen;
	}

	@Override
	public NodeDescriptorRecord setFirstSeen(Instant firstSeen) {
		this.firstSeen = firstSeen;
		return this;
	}

	@Override
	public Instant getLastAnnounced() {
		return lastAnnounced;
	}

	@Override
	public NodeDescriptorRecord setLastAnnounced(Instant lastAnnounced) {
		this.lastAnnounced = lastAnnounced;
		return this;
	}

	@Override
	public String toString() {
		return nodeId + "@" + version + " (" + status + ")";
	}
}
