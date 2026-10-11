package io.metaloom.loom.rest.model.nodeoutput;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

/**
 * Stores what a node emitted for an asset, so a later run of the same node with the same options and inputs can replay it instead of computing.
 *
 * <p>
 * Written by the node itself after it computed, never by a person. Writing again for the same node kind and reuse key replaces the stored output.
 * </p>
 */
public class NodeOutputCreateRequest implements RestRequestModel {

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

	public NodeOutputCreateRequest() {
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public NodeOutputCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getReuseKey() {
		return reuseKey;
	}

	public NodeOutputCreateRequest setReuseKey(String reuseKey) {
		this.reuseKey = reuseKey;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public NodeOutputCreateRequest setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public NodeOutputCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public String getState() {
		return state;
	}

	public NodeOutputCreateRequest setState(String state) {
		this.state = state;
		return this;
	}

	public String getReason() {
		return reason;
	}

	public NodeOutputCreateRequest setReason(String reason) {
		this.reason = reason;
		return this;
	}

	public JsonObject getOutputs() {
		return outputs;
	}

	public NodeOutputCreateRequest setOutputs(JsonObject outputs) {
		this.outputs = outputs;
		return this;
	}

	public String getRunUuid() {
		return runUuid;
	}

	public NodeOutputCreateRequest setRunUuid(String runUuid) {
		this.runUuid = runUuid;
		return this;
	}
}
