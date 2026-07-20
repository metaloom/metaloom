package io.metaloom.loom.rest.model.processor;

import java.time.Instant;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * REST response representing a registered processor node.
 */
public class ProcessorResponse extends AbstractResponse<ProcessorResponse> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Stable node id of the processor. The uuid is derived from this and it is the natural key for correlating REST snapshots with live processor events.")
	private String nodeId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Human readable name of the processor node")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Priority of the processor node. Higher value means higher priority.")
	private Integer priority;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Host address of the processor node")
	private String host;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Current state of the processor node")
	private ProcessorState state;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Capabilities of the processor node")
	private Set<ProcessorCapability> capabilities;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Latest system status reported by the processor node")
	private SystemStatusInfo systemStatus;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Timestamp when the processor was last seen")
	private Instant lastSeen;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Node kinds this worker is permitted to run. An empty/absent whitelist means unrestricted.")
	private Set<String> nodeWhitelist;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Node kinds this worker is explicitly forbidden to run. Takes precedence over the whitelist.")
	private Set<String> nodeBlacklist;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Whether this worker is known in the durable cortex_instance table. Persisted workers remain listed (and their restrictions editable) even while offline.")
	private Boolean persisted;

	public String getNodeId() {
		return nodeId;
	}

	public ProcessorResponse setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getName() {
		return name;
	}

	public ProcessorResponse setName(String name) {
		this.name = name;
		return this;
	}

	public Integer getPriority() {
		return priority;
	}

	public ProcessorResponse setPriority(Integer priority) {
		this.priority = priority;
		return this;
	}

	public String getHost() {
		return host;
	}

	public ProcessorResponse setHost(String host) {
		this.host = host;
		return this;
	}

	public ProcessorState getState() {
		return state;
	}

	public ProcessorResponse setState(ProcessorState state) {
		this.state = state;
		return this;
	}

	public Set<ProcessorCapability> getCapabilities() {
		return capabilities;
	}

	public ProcessorResponse setCapabilities(Set<ProcessorCapability> capabilities) {
		this.capabilities = capabilities;
		return this;
	}

	public SystemStatusInfo getSystemStatus() {
		return systemStatus;
	}

	public ProcessorResponse setSystemStatus(SystemStatusInfo systemStatus) {
		this.systemStatus = systemStatus;
		return this;
	}

	public Instant getLastSeen() {
		return lastSeen;
	}

	public ProcessorResponse setLastSeen(Instant lastSeen) {
		this.lastSeen = lastSeen;
		return this;
	}

	public Set<String> getNodeWhitelist() {
		return nodeWhitelist;
	}

	public ProcessorResponse setNodeWhitelist(Set<String> nodeWhitelist) {
		this.nodeWhitelist = nodeWhitelist;
		return this;
	}

	public Set<String> getNodeBlacklist() {
		return nodeBlacklist;
	}

	public ProcessorResponse setNodeBlacklist(Set<String> nodeBlacklist) {
		this.nodeBlacklist = nodeBlacklist;
		return this;
	}

	public Boolean getPersisted() {
		return persisted;
	}

	public ProcessorResponse setPersisted(Boolean persisted) {
		this.persisted = persisted;
		return this;
	}

	@Override
	public ProcessorResponse self() {
		return this;
	}
}
