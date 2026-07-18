package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

public class PipelineVersionRestoreRequest implements RestRequestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Optional custom name for the restored version (defaults to original name)")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Optional custom description for the restored version (defaults to original description)")
	private String description;

	public PipelineVersionRestoreRequest() {
	}

	public String getName() {
		return name;
	}

	public PipelineVersionRestoreRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public PipelineVersionRestoreRequest setDescription(String description) {
		this.description = description;
		return this;
	}
}