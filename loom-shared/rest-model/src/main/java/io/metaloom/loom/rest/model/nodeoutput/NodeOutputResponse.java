package io.metaloom.loom.rest.model.nodeoutput;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.vertx.core.json.JsonObject;

/**
 * A stored node output: the port values a node emitted for an asset when given the options and inputs its reuse key stands for.
 */
public class NodeOutputResponse implements RestResponseModel<NodeOutputResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Identity of the stored output.")
	private String uuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The asset the node ran against.")
	private String assetUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The node kind that produced the output, e.g. whisper.")
	private String nodeKind;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The hash the node computed over its kind, reuse version, options and non-media inputs. Loom matches it and never parses it.")
	private String reuseKey;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Graph-local id of the node instance that wrote the output. Informational: it is not part of the key, so another pipeline running the same node with the same settings finds this output.")
	private String nodeId;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Model or algorithm version of the producer.")
	private String producerVersion;

	@JsonProperty(required = true)
	@JsonPropertyDescription("SUCCESS or SKIPPED. A failure is never stored for reuse.")
	private String state;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Why the node skipped, when state is SKIPPED.")
	private String reason;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Port id to {contentType, cardinality, values}: the values the node emitted, exactly as they crossed the wire.")
	private JsonObject outputs;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The pipeline run that produced the output, if any.")
	private String runUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("When the output was first stored (ISO-8601).")
	private String created;

	@JsonProperty(required = false)
	@JsonPropertyDescription("When the output was last replaced (ISO-8601).")
	private String edited;

	public NodeOutputResponse() {
	}

	public String getUuid() {
		return uuid;
	}

	public NodeOutputResponse setUuid(String uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getAssetUuid() {
		return assetUuid;
	}

	public NodeOutputResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public NodeOutputResponse setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getReuseKey() {
		return reuseKey;
	}

	public NodeOutputResponse setReuseKey(String reuseKey) {
		this.reuseKey = reuseKey;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public NodeOutputResponse setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public NodeOutputResponse setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public String getState() {
		return state;
	}

	public NodeOutputResponse setState(String state) {
		this.state = state;
		return this;
	}

	public String getReason() {
		return reason;
	}

	public NodeOutputResponse setReason(String reason) {
		this.reason = reason;
		return this;
	}

	public JsonObject getOutputs() {
		return outputs;
	}

	public NodeOutputResponse setOutputs(JsonObject outputs) {
		this.outputs = outputs;
		return this;
	}

	public String getRunUuid() {
		return runUuid;
	}

	public NodeOutputResponse setRunUuid(String runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public String getCreated() {
		return created;
	}

	public NodeOutputResponse setCreated(String created) {
		this.created = created;
		return this;
	}

	public String getEdited() {
		return edited;
	}

	public NodeOutputResponse setEdited(String edited) {
		this.edited = edited;
		return this;
	}

	@Override
	public NodeOutputResponse self() {
		return this;
	}
}
