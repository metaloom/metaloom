package io.metaloom.loom.db.jooq.dao.cortex;

import java.time.Instant;
import java.util.Set;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.cortex.CortexInstance;

public class CortexInstanceImpl extends AbstractEditableElement<CortexInstance> implements CortexInstance {

	private String nodeId;
	private String name;
	private String host;
	private int priority;
	private Instant lastSeen;
	private String state;
	private Instant firstRegistered;

	// The whitelist/blacklist live in the cortex_instance_node_kind child table and are
	// populated/persisted by CortexInstanceDaoImpl; jOOQ does not auto-map them.
	private transient Set<String> nodeWhitelist;
	private transient Set<String> nodeBlacklist;

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public CortexInstance setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public CortexInstance setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getHost() {
		return host;
	}

	@Override
	public CortexInstance setHost(String host) {
		this.host = host;
		return this;
	}

	@Override
	public int getPriority() {
		return priority;
	}

	@Override
	public CortexInstance setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	@Override
	public Instant getLastSeen() {
		return lastSeen;
	}

	@Override
	public CortexInstance setLastSeen(Instant lastSeen) {
		this.lastSeen = lastSeen;
		return this;
	}

	@Override
	public String getState() {
		return state;
	}

	@Override
	public CortexInstance setState(String state) {
		this.state = state;
		return this;
	}

	@Override
	public Instant getFirstRegistered() {
		return firstRegistered;
	}

	@Override
	public CortexInstance setFirstRegistered(Instant firstRegistered) {
		this.firstRegistered = firstRegistered;
		return this;
	}

	@Override
	public Set<String> getNodeWhitelist() {
		return nodeWhitelist;
	}

	@Override
	public CortexInstance setNodeWhitelist(Set<String> nodeWhitelist) {
		this.nodeWhitelist = nodeWhitelist;
		return this;
	}

	@Override
	public Set<String> getNodeBlacklist() {
		return nodeBlacklist;
	}

	@Override
	public CortexInstance setNodeBlacklist(Set<String> nodeBlacklist) {
		this.nodeBlacklist = nodeBlacklist;
		return this;
	}

}
