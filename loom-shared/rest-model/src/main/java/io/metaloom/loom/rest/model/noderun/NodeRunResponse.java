package io.metaloom.loom.rest.model.noderun;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The handle returned when an ad-hoc node run has been accepted.
 *
 * <p>
 * This is answered in milliseconds and says nothing about whether the work succeeded - a graph over
 * two hundred assets takes minutes, and the point of the handle is that the caller does not wait for
 * it. Poll {@code GET /api/v1/node-runs/:uuid} for progress and results, watch the pipeline events
 * socket for live counters, or wait for the notification the run writes when it finishes.
 * </p>
 */
public class NodeRunResponse implements RestResponseModel<NodeRunResponse> {

	@JsonPropertyDescription("Identifier of the started run. Use it with GET /api/v1/node-runs/:uuid.")
	private UUID uuid;

	@JsonPropertyDescription("Status of the run at the moment it was accepted.")
	private String status;

	@JsonPropertyDescription("How many of the requested assets were resolved and fed into the run.")
	private int accepted;

	@JsonPropertyDescription("How many requested assets could not be run.")
	private int rejected;

	@JsonPropertyDescription("The assets that could not be run, typically because they have no stored binary path.")
	private List<UUID> rejectedAssetUuids;

	@JsonPropertyDescription("Rough estimate of how long the run will take, in milliseconds. An estimate, not a commitment.")
	private Long etaMs;

	@JsonPropertyDescription("Human-readable summary of what was accepted.")
	private String message;

	public NodeRunResponse() {
	}

	public UUID getUuid() {
		return uuid;
	}

	public NodeRunResponse setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getStatus() {
		return status;
	}

	public NodeRunResponse setStatus(String status) {
		this.status = status;
		return this;
	}

	public int getAccepted() {
		return accepted;
	}

	public NodeRunResponse setAccepted(int accepted) {
		this.accepted = accepted;
		return this;
	}

	public int getRejected() {
		return rejected;
	}

	public NodeRunResponse setRejected(int rejected) {
		this.rejected = rejected;
		return this;
	}

	public List<UUID> getRejectedAssetUuids() {
		return rejectedAssetUuids;
	}

	public NodeRunResponse setRejectedAssetUuids(List<UUID> rejectedAssetUuids) {
		this.rejectedAssetUuids = rejectedAssetUuids;
		return this;
	}

	public Long getEtaMs() {
		return etaMs;
	}

	public NodeRunResponse setEtaMs(Long etaMs) {
		this.etaMs = etaMs;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public NodeRunResponse setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public NodeRunResponse self() {
		return this;
	}
}
