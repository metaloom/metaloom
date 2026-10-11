package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a pipeline's execution time goes, per node, accumulated over its most recent runs or for one run.
 *
 * <p>
 * Execution time is what the nodes report for their own work, summed over every item - not wall-clock time, which parallel execution makes
 * shorter. Reused executions are counted apart: they cost milliseconds, and the time they saved is estimated from the node's computed executions.
 * </p>
 */
public class PipelineNodeStatsResponse implements RestResponseModel<PipelineNodeStatsResponse> {

	@JsonPropertyDescription("The pipeline.")
	private String pipelineUuid;

	@JsonPropertyDescription("How many runs the figures cover.")
	private int runCount;

	@JsonPropertyDescription("Settled node executions across all nodes.")
	private long tasks;

	@JsonPropertyDescription("Executions that reused a stored result.")
	private long reused;

	@JsonPropertyDescription("Summed execution time of all nodes, in milliseconds.")
	private long totalDurationMs;

	@JsonPropertyDescription("Estimated time reuse saved across all nodes, in milliseconds.")
	private long estimatedSavedMs;

	@JsonPropertyDescription("The runs covered, newest first.")
	private List<PipelineNodeStatsRun> runs = new ArrayList<>();

	@JsonPropertyDescription("Per-node figures, most expensive first.")
	private List<PipelineNodeTimeStats> nodes = new ArrayList<>();

	public String getPipelineUuid() {
		return pipelineUuid;
	}

	public PipelineNodeStatsResponse setPipelineUuid(String pipelineUuid) {
		this.pipelineUuid = pipelineUuid;
		return this;
	}

	public int getRunCount() {
		return runCount;
	}

	public PipelineNodeStatsResponse setRunCount(int runCount) {
		this.runCount = runCount;
		return this;
	}

	public long getTasks() {
		return tasks;
	}

	public PipelineNodeStatsResponse setTasks(long tasks) {
		this.tasks = tasks;
		return this;
	}

	public long getReused() {
		return reused;
	}

	public PipelineNodeStatsResponse setReused(long reused) {
		this.reused = reused;
		return this;
	}

	public long getTotalDurationMs() {
		return totalDurationMs;
	}

	public PipelineNodeStatsResponse setTotalDurationMs(long totalDurationMs) {
		this.totalDurationMs = totalDurationMs;
		return this;
	}

	public long getEstimatedSavedMs() {
		return estimatedSavedMs;
	}

	public PipelineNodeStatsResponse setEstimatedSavedMs(long estimatedSavedMs) {
		this.estimatedSavedMs = estimatedSavedMs;
		return this;
	}

	public List<PipelineNodeStatsRun> getRuns() {
		return runs;
	}

	public PipelineNodeStatsResponse setRuns(List<PipelineNodeStatsRun> runs) {
		this.runs = runs;
		return this;
	}

	public List<PipelineNodeTimeStats> getNodes() {
		return nodes;
	}

	public PipelineNodeStatsResponse setNodes(List<PipelineNodeTimeStats> nodes) {
		this.nodes = nodes;
		return this;
	}

	@Override
	public PipelineNodeStatsResponse self() {
		return this;
	}
}
