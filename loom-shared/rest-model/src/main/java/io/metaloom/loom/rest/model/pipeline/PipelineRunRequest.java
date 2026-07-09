package io.metaloom.loom.rest.model.pipeline;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request payload for triggering a pipeline run.
 *
 * <p>The caller may narrow the media that the pipeline runs against by supplying
 * {@link #getMediaUuids()} or {@link #getPathGlobs()}. If neither is supplied the
 * processor uses the pipeline's own source node to enumerate media.</p>
 */
public class PipelineRunRequest implements RestRequestModel {

	@JsonPropertyDescription("Optional list of asset UUIDs to run the pipeline against.")
	private List<UUID> mediaUuids;

	@JsonPropertyDescription("Optional list of path glob patterns used to select media.")
	private List<String> pathGlobs;

	@JsonPropertyDescription("Override the pipeline's dry-run flag for this run.")
	private Boolean dryRun;

	public PipelineRunRequest() {
	}

	public List<UUID> getMediaUuids() {
		return mediaUuids;
	}

	public PipelineRunRequest setMediaUuids(List<UUID> mediaUuids) {
		this.mediaUuids = mediaUuids;
		return this;
	}

	public List<String> getPathGlobs() {
		return pathGlobs;
	}

	public PipelineRunRequest setPathGlobs(List<String> pathGlobs) {
		this.pathGlobs = pathGlobs;
		return this;
	}

	public Boolean isDryRun() {
		return dryRun;
	}

	public PipelineRunRequest setDryRun(Boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}
}
