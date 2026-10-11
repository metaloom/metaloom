package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * What one node cost in one run.
 */
public class PipelineNodeRunTotal implements RestResponseModel<PipelineNodeRunTotal> {

	@JsonPropertyDescription("The run.")
	private String runUuid;

	@JsonPropertyDescription("Settled executions of the node in this run.")
	private long tasks;

	@JsonPropertyDescription("Executions that reused a result instead of computing it.")
	private long reused;

	@JsonPropertyDescription("Summed execution time of the node in this run, in milliseconds.")
	private long totalDurationMs;

	public String getRunUuid() {
		return runUuid;
	}

	public PipelineNodeRunTotal setRunUuid(String runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public long getTasks() {
		return tasks;
	}

	public PipelineNodeRunTotal setTasks(long tasks) {
		this.tasks = tasks;
		return this;
	}

	public long getReused() {
		return reused;
	}

	public PipelineNodeRunTotal setReused(long reused) {
		this.reused = reused;
		return this;
	}

	public long getTotalDurationMs() {
		return totalDurationMs;
	}

	public PipelineNodeRunTotal setTotalDurationMs(long totalDurationMs) {
		this.totalDurationMs = totalDurationMs;
		return this;
	}

	@Override
	public PipelineNodeRunTotal self() {
		return this;
	}
}
