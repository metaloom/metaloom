package io.metaloom.loom.pipeline.engine;

/**
 * Run-level counters, produced when a run reaches a terminal state.
 *
 * <p>Status is derived rather than tracked: no failures means SUCCESS, all items
 * failing means FAILED, anything in between is PARTIAL. This mirrors the existing
 * {@code PipelineRunStatusResolver} so the two agree once the engine is wired into
 * run tracking.</p>
 */
public class RunSummary {

	private final long mediaCount;
	private final long successCount;
	private final long failureCount;
	private final long skippedCount;
	private final long durationMs;

	RunSummary(long mediaCount, long successCount, long failureCount, long skippedCount, long durationMs) {
		this.mediaCount = mediaCount;
		this.successCount = successCount;
		this.failureCount = failureCount;
		this.skippedCount = skippedCount;
		this.durationMs = durationMs;
	}

	public long getMediaCount() {
		return mediaCount;
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

	public long getDurationMs() {
		return durationMs;
	}

	/**
	 * @return SUCCESS, PARTIAL or FAILED
	 */
	public String getStatus() {
		if (mediaCount == 0) {
			return "SUCCESS";
		}
		if (failureCount == 0) {
			return "SUCCESS";
		}
		if (failureCount >= mediaCount) {
			return "FAILED";
		}
		return "PARTIAL";
	}

	@Override
	public String toString() {
		return "RunSummary[" + getStatus() + ", media=" + mediaCount + ", success=" + successCount
			+ ", failed=" + failureCount + ", skipped=" + skippedCount + ", " + durationMs + "ms]";
	}
}
