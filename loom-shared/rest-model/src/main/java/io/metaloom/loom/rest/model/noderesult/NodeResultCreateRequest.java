package io.metaloom.loom.rest.model.noderesult;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;
import io.vertx.core.json.JsonObject;

/**
 * Create/upsert request for a node result ledger entry. Keyed by {@code (asset, node_kind, node_id)}; re-posting the same key rewrites the row.
 */
public class NodeResultCreateRequest extends AbstractMetaModel<NodeResultCreateRequest>
	implements RestRequestModel, NodeResultModel<NodeResultCreateRequest> {

	private String nodeKind;
	private String nodeId;
	private String producerVersion;
	private String state;
	private String origin;
	private String reason;
	private Long durationMs;
	private JsonObject resultRef;

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public NodeResultCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public NodeResultCreateRequest setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public NodeResultCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public String getState() {
		return state;
	}

	@Override
	public NodeResultCreateRequest setState(String state) {
		this.state = state;
		return this;
	}

	@Override
	public String getOrigin() {
		return origin;
	}

	@Override
	public NodeResultCreateRequest setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

	@Override
	public String getReason() {
		return reason;
	}

	@Override
	public NodeResultCreateRequest setReason(String reason) {
		this.reason = reason;
		return this;
	}

	@Override
	public Long getDurationMs() {
		return durationMs;
	}

	@Override
	public NodeResultCreateRequest setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	@Override
	public JsonObject getResultRef() {
		return resultRef;
	}

	@Override
	public NodeResultCreateRequest setResultRef(JsonObject resultRef) {
		this.resultRef = resultRef;
		return this;
	}

	@Override
	public NodeResultCreateRequest self() {
		return this;
	}

}
