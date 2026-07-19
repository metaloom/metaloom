package io.metaloom.loom.rest.model.pipeline.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Lightweight, JSON-serialisable event dispatched over the pipeline events
 * WebSocket. Carries just enough information for the UI to track node states
 * and volumes — no full media payloads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineEventMessage implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Event type")
	private PipelineEventType type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of the pipeline that emitted this event")
	private String pipelineName;

	@JsonPropertyDescription("UUID of the pipeline_run this event belongs to (null for untracked runs)")
	private String pipelineRunUuid;

	@JsonPropertyDescription("ID of the pipeline node (null for pipeline-level events)")
	private String nodeId;

	@JsonPropertyDescription("Filesystem path of the media item (lightweight reference)")
	private String mediaPath;

	@JsonPropertyDescription("Epoch millis when the event occurred")
	private long timestamp;

	@JsonPropertyDescription("Processing duration in ms (completion events only)")
	private Long durationMs;

	@JsonPropertyDescription("Human-readable detail (failures, skip reasons)")
	private String message;

	// ── Volume / stats fields (NODE_STATS events) ───────────────────────

	@JsonPropertyDescription("Items currently being processed at this node")
	private Integer activeCount;

	@JsonPropertyDescription("Items queued / waiting at this node")
	private Integer pendingCount;

	@JsonPropertyDescription("Total items processed by this node since pipeline start")
	private Long processedCount;

	@JsonPropertyDescription("Total items that failed in this node since pipeline start")
	private Long failedCount;

	@JsonProperty("skippedCount")
	@JsonPropertyDescription("How many items this node skipped rather than processed")
	private Long skippedCount;

	public PipelineEventMessage() {
	}

	public PipelineEventMessage(PipelineEventType type, String pipelineName) {
		this.type = type;
		this.pipelineName = pipelineName;
		this.timestamp = System.currentTimeMillis();
	}

	// ── Getters / setters ───────────────────────────────────────────────

	public PipelineEventType getType() {
		return type;
	}

	public PipelineEventMessage setType(PipelineEventType type) {
		this.type = type;
		return this;
	}

	public String getPipelineName() {
		return pipelineName;
	}

	public PipelineEventMessage setPipelineName(String pipelineName) {
		this.pipelineName = pipelineName;
		return this;
	}

	public String getPipelineRunUuid() {
		return pipelineRunUuid;
	}

	public PipelineEventMessage setPipelineRunUuid(String pipelineRunUuid) {
		this.pipelineRunUuid = pipelineRunUuid;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public PipelineEventMessage setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getMediaPath() {
		return mediaPath;
	}

	public PipelineEventMessage setMediaPath(String mediaPath) {
		this.mediaPath = mediaPath;
		return this;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public PipelineEventMessage setTimestamp(long timestamp) {
		this.timestamp = timestamp;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public PipelineEventMessage setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public PipelineEventMessage setMessage(String message) {
		this.message = message;
		return this;
	}

	public Integer getActiveCount() {
		return activeCount;
	}

	public PipelineEventMessage setActiveCount(Integer activeCount) {
		this.activeCount = activeCount;
		return this;
	}

	public Integer getPendingCount() {
		return pendingCount;
	}

	public PipelineEventMessage setPendingCount(Integer pendingCount) {
		this.pendingCount = pendingCount;
		return this;
	}

	public Long getProcessedCount() {
		return processedCount;
	}

	public PipelineEventMessage setProcessedCount(Long processedCount) {
		this.processedCount = processedCount;
		return this;
	}

	public Long getFailedCount() {
		return failedCount;
	}

	public Long getSkippedCount() {
		return skippedCount;
	}

	public PipelineEventMessage setSkippedCount(Long skippedCount) {
		this.skippedCount = skippedCount;
		return this;
	}

	public PipelineEventMessage setFailedCount(Long failedCount) {
		this.failedCount = failedCount;
		return this;
	}

	@Override
	public String toString() {
		return "PipelineEventMessage{type=" + type + ", pipeline=" + pipelineName + ", node=" + nodeId + "}";
	}
}
