package io.metaloom.loom.db.jooq.dao.asset;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.vertx.core.json.JsonObject;

public class AssetNodeResultImpl extends AbstractEditableElement<AssetNodeResult> implements AssetNodeResult {

	private UUID assetUuid;
	private String nodeKind;
	private String nodeId = "";
	private String producerVersion = "";
	private String state;
	private String origin;
	private String reason;
	private UUID runUuid;
	private UUID taskUuid;
	private Instant started;
	private Instant finished;
	private Long durationMs;
	private JsonObject resultRef;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetNodeResult setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public AssetNodeResult setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getNodeId() {
		return nodeId;
	}

	@Override
	public AssetNodeResult setNodeId(String nodeId) {
		this.nodeId = nodeId == null ? "" : nodeId;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public AssetNodeResult setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion == null ? "" : producerVersion;
		return this;
	}

	@Override
	public String getState() {
		return state;
	}

	@Override
	public AssetNodeResult setState(String state) {
		this.state = state;
		return this;
	}

	@Override
	public String getOrigin() {
		return origin;
	}

	@Override
	public AssetNodeResult setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

	@Override
	public String getReason() {
		return reason;
	}

	@Override
	public AssetNodeResult setReason(String reason) {
		this.reason = reason;
		return this;
	}

	@Override
	public UUID getRunUuid() {
		return runUuid;
	}

	@Override
	public AssetNodeResult setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	@Override
	public UUID getTaskUuid() {
		return taskUuid;
	}

	@Override
	public AssetNodeResult setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

	@Override
	public Instant getStarted() {
		return started;
	}

	@Override
	public AssetNodeResult setStarted(Instant started) {
		this.started = started;
		return this;
	}

	@Override
	public Instant getFinished() {
		return finished;
	}

	@Override
	public AssetNodeResult setFinished(Instant finished) {
		this.finished = finished;
		return this;
	}

	@Override
	public Long getDurationMs() {
		return durationMs;
	}

	@Override
	public AssetNodeResult setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	@Override
	public JsonObject getResultRef() {
		return resultRef;
	}

	@Override
	public AssetNodeResult setResultRef(JsonObject resultRef) {
		this.resultRef = resultRef;
		return this;
	}
}
