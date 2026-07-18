package io.metaloom.loom.rest.model.processor.message;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Instructs a processor to run a source node and stream back what it enumerates.
 *
 * <p>Body of {@link ProcessorMessageType#SOURCE_TASK}. The processor answers with
 * a sequence of {@link SourceItemsMessage} batches followed by one
 * {@link SourceCompleteMessage}.</p>
 */
public class SourceTaskMessage implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The pipeline run this source task belongs to")
	private UUID runUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Id of the source node within the pipeline graph")
	private String nodeId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Node kind to run, e.g. filesystem-source")
	private String nodeKind;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Per-node options from the pipeline definition, e.g. path or pathGlobs")
	private Map<String, Object> options;

	@JsonProperty(required = false)
	@JsonPropertyDescription("How many items the processor should send per batch")
	private int batchSize = 250;

	public UUID getRunUuid() {
		return runUuid;
	}

	public SourceTaskMessage setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public SourceTaskMessage setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public SourceTaskMessage setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public Map<String, Object> getOptions() {
		return options;
	}

	public SourceTaskMessage setOptions(Map<String, Object> options) {
		this.options = options;
		return this;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public SourceTaskMessage setBatchSize(int batchSize) {
		this.batchSize = batchSize;
		return this;
	}
}
