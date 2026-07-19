package io.metaloom.loom.rest.model.processor.message;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;

/**
 * Registration payload sent by a processor node when it first connects.
 */
public class ProcessorRegistration implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Unique identifier of the processor node")
	private String nodeId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Human readable name of the processor node")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Priority of the processor node (higher = preferred)")
	private int priority;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Host address of the processor node (e.g. 10.0.1.10:9090)")
	private String host;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Capabilities offered by this processor node")
	private Set<ProcessorCapability> capabilities;

	@JsonProperty("nodeKinds")
	@JsonPropertyDescription("Node kinds this processor is willing to execute. An empty or absent set means it "
		+ "accepts any kind, which is how a worker that predates whitelisting behaves.")
	private Set<String> nodeKinds;

	public String getNodeId() {
		return nodeId;
	}

	public ProcessorRegistration setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getName() {
		return name;
	}

	public ProcessorRegistration setName(String name) {
		this.name = name;
		return this;
	}

	public int getPriority() {
		return priority;
	}

	public ProcessorRegistration setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	public String getHost() {
		return host;
	}

	public ProcessorRegistration setHost(String host) {
		this.host = host;
		return this;
	}

	/**
	 * Restrict this worker to a subset of node kinds.
	 *
	 * <p>This is what lets a deployment dedicate machines to particular work - GPU
	 * boxes to embeddings, a single host with the media mount to filesystem sources -
	 * instead of every worker having to be able to do everything.</p>
	 *
	 * @return the accepted kinds, or null/empty when the worker accepts anything
	 */
	public Set<String> getNodeKinds() {
		return nodeKinds;
	}

	public ProcessorRegistration setNodeKinds(Set<String> nodeKinds) {
		this.nodeKinds = nodeKinds;
		return this;
	}

	public Set<ProcessorCapability> getCapabilities() {
		return capabilities;
	}

	public ProcessorRegistration setCapabilities(Set<ProcessorCapability> capabilities) {
		this.capabilities = capabilities;
		return this;
	}
}
