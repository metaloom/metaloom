package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class PipelineCreateRequest implements RestRequestModel, PipelineModel<PipelineCreateRequest> {

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

	@Override
	public String getName() {
		return name;
	}

	@Override
	public PipelineCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public PipelineCreateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public JsonObject getDefinition() {
		return definition;
	}

	@Override
	public PipelineCreateRequest setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}

	@Override
	public Boolean isEnabled() {
		return enabled;
	}

	@Override
	public PipelineCreateRequest setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public Integer getPriority() {
		return priority;
	}

	@Override
	public PipelineCreateRequest setPriority(Integer priority) {
		this.priority = priority;
		return this;
	}

	@Override
	public Boolean isDryRun() {
		return dryRun;
	}

	@Override
	public PipelineCreateRequest setDryRun(Boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public PipelineCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public PipelineCreateRequest self() {
		return this;
	}

}
