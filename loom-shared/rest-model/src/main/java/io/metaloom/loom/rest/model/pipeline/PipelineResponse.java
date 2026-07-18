package io.metaloom.loom.rest.model.pipeline;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.vertx.core.json.JsonObject;

/**
 * Flattened pipeline response. The {@code uuid} identifies the pipeline; {@link #getVersionUuid()} and {@link #getVersionNumber()} identify the version the
 * remaining fields were rendered from.
 */
public class PipelineResponse extends AbstractCreatorEditorRestResponse<PipelineResponse> implements PipelineModel<PipelineResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The UUID of the pipeline version this response was rendered from")
	private UUID versionUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The version number this response was rendered from")
	private Integer versionNumber;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The name of the pipeline")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The description of the pipeline")
	private String description;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The pipeline definition containing nodes and their configuration")
	private JsonObject definition;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Whether the pipeline is enabled")
	private Boolean enabled;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The priority of the pipeline")
	private Integer priority;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Whether the pipeline is in dry-run mode")
	private Boolean dryRun;

	public PipelineResponse() {
	}

	@Override
	public UUID getVersionUuid() {
		return versionUuid;
	}

	@Override
	public PipelineResponse setVersionUuid(UUID versionUuid) {
		this.versionUuid = versionUuid;
		return this;
	}

	@Override
	public Integer getVersionNumber() {
		return versionNumber;
	}

	@Override
	public PipelineResponse setVersionNumber(Integer versionNumber) {
		this.versionNumber = versionNumber;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public PipelineResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public PipelineResponse setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public JsonObject getDefinition() {
		return definition;
	}

	@Override
	public PipelineResponse setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}

	@Override
	public Boolean isEnabled() {
		return enabled;
	}

	@Override
	public PipelineResponse setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public Integer getPriority() {
		return priority;
	}

	@Override
	public PipelineResponse setPriority(Integer priority) {
		this.priority = priority;
		return this;
	}

	@Override
	public Boolean isDryRun() {
		return dryRun;
	}

	@Override
	public PipelineResponse setDryRun(Boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	@Override
	public PipelineResponse self() {
		return this;
	}
}
