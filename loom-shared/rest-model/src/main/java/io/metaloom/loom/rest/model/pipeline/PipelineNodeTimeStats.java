package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Where one node's time went across the runs a {@link PipelineNodeStatsResponse} covers.
 */
public class PipelineNodeTimeStats implements RestResponseModel<PipelineNodeTimeStats> {

	@JsonPropertyDescription("The graph-local node id.")
	private String nodeId;

	@JsonPropertyDescription("The node kind.")
	private String nodeKind;

	@JsonPropertyDescription("Settled executions.")
	private long tasks;

	@JsonPropertyDescription("Completed executions that did the work.")
	private long computed;

	@JsonPropertyDescription("Completed executions that reused a stored result instead of computing.")
	private long reused;

	@JsonPropertyDescription("Failed executions.")
	private long failed;

	@JsonPropertyDescription("Skipped executions.")
	private long skipped;

	@JsonPropertyDescription("Summed execution time of every settled execution, in milliseconds.")
	private long totalDurationMs;

	@JsonPropertyDescription("Summed execution time of the computed executions, in milliseconds.")
	private long computedDurationMs;

	@JsonPropertyDescription("Mean duration of a computed execution; null without any.")
	private Long avgDurationMs;

	@JsonPropertyDescription("Median duration of a computed execution; null without any.")
	private Long p50DurationMs;

	@JsonPropertyDescription("95th percentile duration of a computed execution; null without any.")
	private Long p95DurationMs;

	@JsonPropertyDescription("Slowest computed execution; null without any.")
	private Long maxDurationMs;

	@JsonPropertyDescription("Time reuse saved: reused executions times the mean computed duration. An estimate.")
	private long estimatedSavedMs;

	@JsonPropertyDescription("This node's fraction of the total execution time of all nodes, 0 to 1.")
	private double share;

	@JsonPropertyDescription("Distribution of computed durations in power-of-two buckets.")
	private List<PipelineNodeDurationBucket> histogram = new ArrayList<>();

	@JsonPropertyDescription("The node's time per run, newest run first.")
	private List<PipelineNodeRunTotal> runs = new ArrayList<>();

	public String getNodeId() {
		return nodeId;
	}

	public PipelineNodeTimeStats setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public PipelineNodeTimeStats setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public long getTasks() {
		return tasks;
	}

	public PipelineNodeTimeStats setTasks(long tasks) {
		this.tasks = tasks;
		return this;
	}

	public long getComputed() {
		return computed;
	}

	public PipelineNodeTimeStats setComputed(long computed) {
		this.computed = computed;
		return this;
	}

	public long getReused() {
		return reused;
	}

	public PipelineNodeTimeStats setReused(long reused) {
		this.reused = reused;
		return this;
	}

	public long getFailed() {
		return failed;
	}

	public PipelineNodeTimeStats setFailed(long failed) {
		this.failed = failed;
		return this;
	}

	public long getSkipped() {
		return skipped;
	}

	public PipelineNodeTimeStats setSkipped(long skipped) {
		this.skipped = skipped;
		return this;
	}

	public long getTotalDurationMs() {
		return totalDurationMs;
	}

	public PipelineNodeTimeStats setTotalDurationMs(long totalDurationMs) {
		this.totalDurationMs = totalDurationMs;
		return this;
	}

	public long getComputedDurationMs() {
		return computedDurationMs;
	}

	public PipelineNodeTimeStats setComputedDurationMs(long computedDurationMs) {
		this.computedDurationMs = computedDurationMs;
		return this;
	}

	public Long getAvgDurationMs() {
		return avgDurationMs;
	}

	public PipelineNodeTimeStats setAvgDurationMs(Long avgDurationMs) {
		this.avgDurationMs = avgDurationMs;
		return this;
	}

	public Long getP50DurationMs() {
		return p50DurationMs;
	}

	public PipelineNodeTimeStats setP50DurationMs(Long p50DurationMs) {
		this.p50DurationMs = p50DurationMs;
		return this;
	}

	public Long getP95DurationMs() {
		return p95DurationMs;
	}

	public PipelineNodeTimeStats setP95DurationMs(Long p95DurationMs) {
		this.p95DurationMs = p95DurationMs;
		return this;
	}

	public Long getMaxDurationMs() {
		return maxDurationMs;
	}

	public PipelineNodeTimeStats setMaxDurationMs(Long maxDurationMs) {
		this.maxDurationMs = maxDurationMs;
		return this;
	}

	public long getEstimatedSavedMs() {
		return estimatedSavedMs;
	}

	public PipelineNodeTimeStats setEstimatedSavedMs(long estimatedSavedMs) {
		this.estimatedSavedMs = estimatedSavedMs;
		return this;
	}

	public double getShare() {
		return share;
	}

	public PipelineNodeTimeStats setShare(double share) {
		this.share = share;
		return this;
	}

	public List<PipelineNodeDurationBucket> getHistogram() {
		return histogram;
	}

	public PipelineNodeTimeStats setHistogram(List<PipelineNodeDurationBucket> histogram) {
		this.histogram = histogram;
		return this;
	}

	public List<PipelineNodeRunTotal> getRuns() {
		return runs;
	}

	public PipelineNodeTimeStats setRuns(List<PipelineNodeRunTotal> runs) {
		this.runs = runs;
		return this;
	}

	@Override
	public PipelineNodeTimeStats self() {
		return this;
	}
}
