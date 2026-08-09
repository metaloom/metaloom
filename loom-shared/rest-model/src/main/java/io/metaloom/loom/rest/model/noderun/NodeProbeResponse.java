package io.metaloom.loom.rest.model.noderun;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.vertx.core.json.JsonObject;

/**
 * The settled result of a single-node probe.
 *
 * <p>
 * A probe that was refused - unknown kind, invalid options, no worker, timeout - is still a
 * <em>response</em>, carrying the reason in {@link #getMessage()} and a non-success {@link #getState()}.
 * It is not an HTTP error: the request was well formed and the answer is "this could not be run, and
 * here is why", which is something a caller and a language model can both act on.
 * </p>
 */
public class NodeProbeResponse implements RestResponseModel<NodeProbeResponse> {

	@JsonPropertyDescription("Outcome of the node: COMPLETED, FAILED, SKIPPED, or REJECTED when it never reached a worker.")
	private String state;

	@JsonPropertyDescription("The node kind that was run.")
	private String nodeKind;

	@JsonPropertyDescription("The asset the node ran against.")
	private UUID assetUuid;

	@JsonPropertyDescription("How long the node itself took, in milliseconds. Null when nothing ran.")
	private Long durationMs;

	@JsonPropertyDescription("Port payloads the node produced, keyed by output port id.")
	private JsonObject outputs;

	@JsonPropertyDescription("The same result rendered as bounded plain text, for a caller that wants to read it rather than parse it.")
	private String text;

	@JsonPropertyDescription("Why the node failed, was skipped, or was refused. Null on a clean success.")
	private String message;

	public NodeProbeResponse() {
	}

	public String getState() {
		return state;
	}

	public NodeProbeResponse setState(String state) {
		this.state = state;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public NodeProbeResponse setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public NodeProbeResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public NodeProbeResponse setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public JsonObject getOutputs() {
		return outputs;
	}

	public NodeProbeResponse setOutputs(JsonObject outputs) {
		this.outputs = outputs;
		return this;
	}

	public String getText() {
		return text;
	}

	public NodeProbeResponse setText(String text) {
		this.text = text;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public NodeProbeResponse setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public NodeProbeResponse self() {
		return this;
	}
}
