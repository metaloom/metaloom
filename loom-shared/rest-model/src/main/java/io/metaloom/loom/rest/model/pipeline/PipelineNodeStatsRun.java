package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One run covered by a {@link PipelineNodeStatsResponse}.
 */
public class PipelineNodeStatsRun implements RestResponseModel<PipelineNodeStatsRun> {

	@JsonPropertyDescription("The run.")
	private String uuid;

	@JsonPropertyDescription("The run status.")
	private String status;

	@JsonPropertyDescription("When the run started, epoch milliseconds.")
	private Long started;

	@JsonPropertyDescription("Wall-clock duration of the run, in milliseconds.")
	private Long durationMs;

	@JsonPropertyDescription("The pipeline version the run executed.")
	private int pipelineVersion;

	public String getUuid() {
		return uuid;
	}

	public PipelineNodeStatsRun setUuid(String uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getStatus() {
		return status;
	}

	public PipelineNodeStatsRun setStatus(String status) {
		this.status = status;
		return this;
	}

	public Long getStarted() {
		return started;
	}

	public PipelineNodeStatsRun setStarted(Long started) {
		this.started = started;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public PipelineNodeStatsRun setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public int getPipelineVersion() {
		return pipelineVersion;
	}

	public PipelineNodeStatsRun setPipelineVersion(int pipelineVersion) {
		this.pipelineVersion = pipelineVersion;
		return this;
	}

	@Override
	public PipelineNodeStatsRun self() {
		return this;
	}
}
