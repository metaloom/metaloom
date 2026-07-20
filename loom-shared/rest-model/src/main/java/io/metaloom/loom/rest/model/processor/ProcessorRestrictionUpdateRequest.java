package io.metaloom.loom.rest.model.processor;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request to update the administrator-managed node-kind restrictions of a cortex instance.
 *
 * <p>Both lists replace whatever was persisted before. An empty/absent whitelist means the
 * worker is unrestricted; the blacklist takes precedence over the whitelist for any kind that
 * appears in both.</p>
 */
public class ProcessorRestrictionUpdateRequest implements RestRequestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Node kinds this worker is permitted to run. An empty/absent whitelist means unrestricted.")
	private Set<String> nodeWhitelist;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Node kinds this worker is explicitly forbidden to run. Takes precedence over the whitelist.")
	private Set<String> nodeBlacklist;

	public Set<String> getNodeWhitelist() {
		return nodeWhitelist;
	}

	public ProcessorRestrictionUpdateRequest setNodeWhitelist(Set<String> nodeWhitelist) {
		this.nodeWhitelist = nodeWhitelist;
		return this;
	}

	public Set<String> getNodeBlacklist() {
		return nodeBlacklist;
	}

	public ProcessorRestrictionUpdateRequest setNodeBlacklist(Set<String> nodeBlacklist) {
		this.nodeBlacklist = nodeBlacklist;
		return this;
	}

}
