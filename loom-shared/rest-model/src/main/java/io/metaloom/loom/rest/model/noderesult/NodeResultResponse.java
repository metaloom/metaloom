package io.metaloom.loom.rest.model.noderesult;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.vertx.core.json.JsonObject;

public class NodeResultResponse extends AbstractCreatorEditorRestResponse<NodeResultResponse>
	implements NodeResultModel<NodeResultResponse> {

	private String assetUuid;
	private String nodeKind;
	private String nodeId;
	private String producerVersion;
	private String state;
	private String origin;
	private String reason;
	private Long durationMs;
	private JsonObject resultRef;
	private String runUuid;
	private String taskUuid;

	public String getAssetUuid() {
		return assetUuid;
	}

	public NodeResultResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public NodeResultResponse setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public NodeResultResponse setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public NodeResultResponse setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public String getState() {
		return state;
	}

	@Override
	public NodeResultResponse setState(String state) {
		this.state = state;
		return this;
	}

	@Override
	public String getOrigin() {
		return origin;
	}

	@Override
	public NodeResultResponse setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

	@Override
	public String getReason() {
		return reason;
	}

	@Override
	public NodeResultResponse setReason(String reason) {
		this.reason = reason;
		return this;
	}

	@Override
	public Long getDurationMs() {
		return durationMs;
	}

	@Override
	public NodeResultResponse setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	@Override
	public JsonObject getResultRef() {
		return resultRef;
	}

	@Override
	public NodeResultResponse setResultRef(JsonObject resultRef) {
		this.resultRef = resultRef;
		return this;
	}

	@Override
	public String getRunUuid() {
		return runUuid;
	}

	@Override
	public NodeResultResponse setRunUuid(String runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	@Override
	public String getTaskUuid() {
		return taskUuid;
	}

	@Override
	public NodeResultResponse setTaskUuid(String taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

	@Override
	public NodeResultResponse self() {
		return this;
	}

}
