package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class PipelineCreateRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The name of the pipeline.")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The description of the pipeline.")
	private String description;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The pipeline definition containing nodes and their configuration.")
	private JsonObject definition;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Whether the pipeline is enabled.")
	private Boolean enabled;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The priority of the pipeline.")
	private Integer priority;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Whether the pipeline is in dry-run mode.")
	private Boolean dryRun;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public PipelineCreateRequest() {
	}

	public String getName() {
		return name;
	}

	public PipelineCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public PipelineCreateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	public JsonObject getDefinition() {
		return definition;
	}

	public PipelineCreateRequest setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}

	public Boolean isEnabled() {
		return enabled;
	}

	public PipelineCreateRequest setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public Integer getPriority() {
		return priority;
	}

	public PipelineCreateRequest setPriority(Integer priority) {
		this.priority = priority;
		return this;
	}

	public Boolean isDryRun() {
		return dryRun;
	}

	public PipelineCreateRequest setDryRun(Boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	public JsonObject getMeta() {
		return meta;
	}

	public PipelineCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	public PipelineCreateRequest self() {
		return this;
	}

}
