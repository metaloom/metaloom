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

	@Override
	public ProcessorResponse self() {
		return this;
	}
}
