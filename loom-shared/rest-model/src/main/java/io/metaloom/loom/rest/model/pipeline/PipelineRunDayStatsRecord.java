package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Aggregated pipeline run counters for a single calendar day.
 */
public class PipelineRunDayStatsRecord implements RestModel {

	@JsonPropertyDescription("Calendar day of the bucket (ISO 8601 date, e.g. 2026-07-22).")
	private String date;

	@JsonPropertyDescription("Number of pipeline runs started on this day (across all pipelines).")
	private long runCount;

	@JsonPropertyDescription("Sum of successfully processed media items of runs started on this day.")
	private long successCount;

	@JsonPropertyDescription("Sum of failed media items of runs started on this day.")
	private long failureCount;

	@JsonPropertyDescription("Sum of skipped media items of runs started on this day.")
	private long skippedCount;

	public PipelineRunDayStatsRecord() {
	}

	public String getDate() {
		return date;
	}

	public PipelineRunDayStatsRecord setDate(String date) {
		this.date = date;
		return this;
	}

	public long getRunCount() {
		return runCount;
	}

	public PipelineRunDayStatsRecord setRunCount(long runCount) {
		this.runCount = runCount;
		return this;
	}

	public long getSuccessCount() {
		return successCount;
	}

	public PipelineRunDayStatsRecord setSuccessCount(long successCount) {
		this.successCount = successCount;
		return this;
	}

	public long getFailureCount() {
		return failureCount;
	}

	public PipelineRunDayStatsRecord setFailureCount(long failureCount) {
		this.failureCount = failureCount;
		return this;
	}

	public long getSkippedCount() {
		return skippedCount;
	}

	public PipelineRunDayStatsRecord setSkippedCount(long skippedCount) {
		this.skippedCount = skippedCount;
		return this;
	}

}
