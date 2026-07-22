package io.metaloom.loom.db.model.pipeline;

import java.time.LocalDate;

/**
 * Aggregated pipeline run counters for a single calendar day (bucketed by {@link PipelineRun#getStarted()}).
 */
public class PipelineRunDayStats {

	private final LocalDate date;
	private final long runCount;
	private final long successCount;
	private final long failureCount;
	private final long skippedCount;

	public PipelineRunDayStats(LocalDate date, long runCount, long successCount, long failureCount, long skippedCount) {
		this.date = date;
		this.runCount = runCount;
		this.successCount = successCount;
		this.failureCount = failureCount;
		this.skippedCount = skippedCount;
	}

	public LocalDate getDate() {
		return date;
	}

	public long getRunCount() {
		return runCount;
	}

	public long getSuccessCount() {
		return successCount;
	}

	public long getFailureCount() {
		return failureCount;
	}

	public long getSkippedCount() {
		return skippedCount;
	}

}
