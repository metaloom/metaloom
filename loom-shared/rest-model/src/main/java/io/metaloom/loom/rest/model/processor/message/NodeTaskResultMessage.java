package io.metaloom.loom.rest.model.processor.message;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.rest.model.RestModel;

/**
 * Reports the outcome of a single {@link io.metaloom.loom.pipeline.model.NodeTask}.
 *
 * <p>Body of {@link ProcessorMessageType#NODE_TASK_RESULT}. The run and item ids
 * are carried alongside the result so the engine can route it without keeping a
 * task-to-item map of its own.</p>
 */
public class NodeTaskResultMessage implements RestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The pipeline run; null for an untracked execution")
	private UUID runUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The media item this result belongs to")
	private String itemId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The node outcome")
	private NodeTaskResult result;

	public UUID getRunUuid() {
		return runUuid;
	}

	public NodeTaskResultMessage setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public String getItemId() {
		return itemId;
	}

	public NodeTaskResultMessage setItemId(String itemId) {
		this.itemId = itemId;
		return this;
	}

	public NodeTaskResult getResult() {
		return result;
	}

	public NodeTaskResultMessage setResult(NodeTaskResult result) {
		this.result = result;
		return this;
	}
}
