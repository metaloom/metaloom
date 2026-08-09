package io.metaloom.loom.rest.model.noderun;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request payload for running one node against one asset and waiting for the result.
 *
 * <p>
 * A probe is the small case on purpose: one node, one asset, answered inside the request. Anything
 * larger - more than one node, or more than one asset - is a {@link NodeRunRequest}, which returns a
 * handle instead of blocking. A probe that does not finish inside {@code LOOM_AGENT_PROBE_TIMEOUT_MS}
 * is reported as a timeout rather than waited on, so a caller can never be held indefinitely.
 * </p>
 */
public class NodeProbeRequest implements RestRequestModel {

	@JsonPropertyDescription("The node kind to run, e.g. sha512 or vlm.")
	private String kind;

	@JsonPropertyDescription("The asset to run the node against.")
	private UUID assetUuid;

	@JsonPropertyDescription("Node options, validated against the node's declared parameters before anything is dispatched.")
	private Map<String, Object> options;

	@JsonPropertyDescription("Record the result in the per-asset processing ledger under an 'adhoc:' node id. Defaults to LOOM_AGENT_EXEC_PERSIST_DEFAULT (off), which writes nothing.")
	private Boolean persist;

	public NodeProbeRequest() {
	}

	public String getKind() {
		return kind;
	}

	public NodeProbeRequest setKind(String kind) {
		this.kind = kind;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public NodeProbeRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public Map<String, Object> getOptions() {
		return options;
	}

	public NodeProbeRequest setOptions(Map<String, Object> options) {
		this.options = options;
		return this;
	}

	public Boolean getPersist() {
		return persist;
	}

	public NodeProbeRequest setPersist(Boolean persist) {
		this.persist = persist;
		return this;
	}

}
