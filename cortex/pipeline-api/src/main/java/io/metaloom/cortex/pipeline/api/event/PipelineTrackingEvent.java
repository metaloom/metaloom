package io.metaloom.cortex.pipeline.api.event;

/**
 * Lightweight tracking event emitted by the pipeline executor at key
 * lifecycle points. These events carry only scalar data (strings, longs)
 * so they can be serialised cheaply for WebSocket dispatch.
 *
 * <p>Unlike {@link NodeCompletionEvent} (which carries the full
 * {@code LoomMedia} and {@code NodeResult}), a tracking event is designed
 * for high-volume, low-overhead observability.</p>
 */
public class PipelineTrackingEvent {

	/**
	 * Tracking event types aligned with
	 * {@code io.metaloom.loom.rest.model.pipeline.event.PipelineEventType}.
	 */
	public enum Type {
		PIPELINE_STARTED,
		PIPELINE_COMPLETED,
		NODE_STARTED,
		NODE_COMPLETED,
		NODE_FAILED,
		NODE_SKIPPED,
		NODE_BUFFERED,
		NODE_STATS
	}

	private final Type type;
	private final String pipelineName;
	private final String nodeId;
	private final String mediaPath;
	private final long timestamp;
	private final long durationMs;
	private final String message;

	/**
	 * UUID of the Loom {@code pipeline_run} this event belongs to, or
	 * {@code null} for untracked (offline / CLI) executions.
	 */
	private final String pipelineRunUuid;

	/**
	 * Per-media aggregate counters. Populated only on
	 * {@link Type#PIPELINE_COMPLETED}; {@code null} on every other event type.
	 */
	private final RunCounters counters;

	public PipelineTrackingEvent(Type type, String pipelineName, String nodeId, String mediaPath) {
		this(type, pipelineName, nodeId, mediaPath, 0, null, null);
	}

	public PipelineTrackingEvent(Type type, String pipelineName, String nodeId, String mediaPath,
			long durationMs, String message) {
		this(type, pipelineName, nodeId, mediaPath, durationMs, message, null);
	}

	public PipelineTrackingEvent(Type type, String pipelineName, String nodeId, String mediaPath,
			long durationMs, String message, String pipelineRunUuid) {
		this(type, pipelineName, nodeId, mediaPath, durationMs, message, pipelineRunUuid, null);
	}

	private PipelineTrackingEvent(Type type, String pipelineName, String nodeId, String mediaPath,
			long durationMs, String message, String pipelineRunUuid, RunCounters counters) {
		this.type = type;
		this.pipelineName = pipelineName;
		this.nodeId = nodeId;
		this.mediaPath = mediaPath;
		this.timestamp = System.currentTimeMillis();
		this.durationMs = durationMs;
		this.message = message;
		this.pipelineRunUuid = pipelineRunUuid;
		this.counters = counters;
	}

	/**
	 * Build a {@link Type#PIPELINE_COMPLETED} event carrying the real elapsed
	 * time and the per-media aggregate counters for the run. Loom uses these to
	 * close out the {@code pipeline_run} record.
	 */
	public static PipelineTrackingEvent pipelineCompleted(String pipelineName, String pipelineRunUuid,
			long durationMs, RunCounters counters, String message) {
		return new PipelineTrackingEvent(Type.PIPELINE_COMPLETED, pipelineName, null, null,
				durationMs, message, pipelineRunUuid, counters);
	}

	/**
	 * Immutable per-media aggregate counters for one pipeline run.
	 *
	 * <p>These count <em>media items</em>, not node executions:
	 * {@code media == success + failure + skipped}.</p>
	 */
	public static final class RunCounters {

		private final int mediaCount;
		private final int successCount;
		private final int failureCount;
		private final int skippedCount;

		public RunCounters(int mediaCount, int successCount, int failureCount, int skippedCount) {
			this.mediaCount = mediaCount;
			this.successCount = successCount;
			this.failureCount = failureCount;
			this.skippedCount = skippedCount;
		}

		public int getMediaCount() {
			return mediaCount;
		}

		public int getSuccessCount() {
			return successCount;
		}

		public int getFailureCount() {
			return failureCount;
		}

		public int getSkippedCount() {
			return skippedCount;
		}

		@Override
		public String toString() {
			return "media=" + mediaCount + " success=" + successCount
					+ " failure=" + failureCount + " skipped=" + skippedCount;
		}
	}

	public Type getType() {
		return type;
	}

	public String getPipelineName() {
		return pipelineName;
	}

	public String getNodeId() {
		return nodeId;
	}

	public String getMediaPath() {
		return mediaPath;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public String getMessage() {
		return message;
	}

	/**
	 * UUID of the Loom {@code pipeline_run} this event belongs to, or
	 * {@code null} when the execution is not tracked by Loom.
	 */
	public String getPipelineRunUuid() {
		return pipelineRunUuid;
	}

	/**
	 * Per-media aggregate counters, or {@code null} for every event type other
	 * than {@link Type#PIPELINE_COMPLETED}.
	 */
	public RunCounters getCounters() {
		return counters;
	}

	@Override
	public String toString() {
		return "PipelineTrackingEvent{" + type + ", pipeline=" + pipelineName
				+ ", node=" + nodeId + ", media=" + mediaPath
				+ (pipelineRunUuid != null ? ", run=" + pipelineRunUuid : "")
				+ (counters != null ? ", " + counters : "") + "}";
	}
}
