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

	public PipelineTrackingEvent(Type type, String pipelineName, String nodeId, String mediaPath) {
		this(type, pipelineName, nodeId, mediaPath, 0, null);
	}

	public PipelineTrackingEvent(Type type, String pipelineName, String nodeId, String mediaPath,
			long durationMs, String message) {
		this.type = type;
		this.pipelineName = pipelineName;
		this.nodeId = nodeId;
		this.mediaPath = mediaPath;
		this.timestamp = System.currentTimeMillis();
		this.durationMs = durationMs;
		this.message = message;
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

	@Override
	public String toString() {
		return "PipelineTrackingEvent{" + type + ", pipeline=" + pipelineName
				+ ", node=" + nodeId + ", media=" + mediaPath + "}";
	}
}
