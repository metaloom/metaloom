package io.metaloom.loom.rest.model.noderun;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.vertx.core.json.JsonObject;

/**
 * What one node produced for one item of an ad-hoc run.
 *
 * <p>
 * Read from the persisted task rows rather than from the live engine, so a run is just as readable
 * after it has finished - or after a Loom restart - as it is while it is going.
 * </p>
 */
public class NodeRunItemResult {

	@JsonPropertyDescription("The asset this result belongs to, when the item could be matched to one.")
	private UUID assetUuid;

	@JsonPropertyDescription("Path of the media the node ran against.")
	private String mediaPath;

	@JsonPropertyDescription("Graph-local id of the node that produced the result.")
	private String nodeId;

	@JsonPropertyDescription("Kind of the node that produced the result.")
	private String nodeKind;

	@JsonPropertyDescription("State of the node task: COMPLETED, FAILED, SKIPPED, RUNNING or PENDING.")
	private String state;

	@JsonPropertyDescription("Port payloads the node produced, keyed by output port id.")
	private JsonObject outputs;

	@JsonPropertyDescription("Why the node failed or was skipped.")
	private String message;

	@JsonPropertyDescription("How long the node took, in milliseconds.")
	private Long durationMs;

	public NodeRunItemResult() {
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public NodeRunItemResult setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getMediaPath() {
		return mediaPath;
	}

	public NodeRunItemResult setMediaPath(String mediaPath) {
		this.mediaPath = mediaPath;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public NodeRunItemResult setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public NodeRunItemResult setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getState() {
		return state;
	}

	public NodeRunItemResult setState(String state) {
		this.state = state;
		return this;
	}

	public JsonObject getOutputs() {
		return outputs;
	}

	public NodeRunItemResult setOutputs(JsonObject outputs) {
		this.outputs = outputs;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public NodeRunItemResult setMessage(String message) {
		this.message = message;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public NodeRunItemResult setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

}
