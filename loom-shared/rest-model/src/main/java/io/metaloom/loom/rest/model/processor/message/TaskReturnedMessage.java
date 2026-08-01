package io.metaloom.loom.rest.model.processor.message;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Hands a dispatched task back to Loom without a result.
 *
 * <p>Body of {@link ProcessorMessageType#TASK_RETURNED}. A worker that is shutting
 * down sends one of these for every task it will not finish, which lets Loom place
 * the work elsewhere immediately. The alternative - saying nothing - costs the run a
 * full lease interval per task before the reaper notices, which is the whole reason
 * this message exists.</p>
 *
 * <p>A return is <em>not</em> a failure. The work never ran, so it does not consume
 * the execution's attempt budget; see {@code PipelineRunEngine#onNodeTaskReturned}.</p>
 */
public class TaskReturnedMessage implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The pipeline run the task belongs to")
	private UUID runUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The media item the task was dispatched for")
	private String itemId;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The dispatched task, for correlation with the task row")
	private UUID taskUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Graph-local ids of the nodes being handed back - one for a node task, "
		+ "every member for a segment task")
	private List<String> nodeIds;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Which element of a fanned-out sequence; 0 for a node that runs once per item")
	private int elementSeq;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Why the worker gave the task back, recorded on the re-dispatch")
	private String reason;

	public UUID getRunUuid() {
		return runUuid;
	}

	public TaskReturnedMessage setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public String getItemId() {
		return itemId;
	}

	public TaskReturnedMessage setItemId(String itemId) {
		this.itemId = itemId;
		return this;
	}

	public UUID getTaskUuid() {
		return taskUuid;
	}

	public TaskReturnedMessage setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

	public List<String> getNodeIds() {
		return nodeIds;
	}

	public TaskReturnedMessage setNodeIds(List<String> nodeIds) {
		this.nodeIds = nodeIds;
		return this;
	}

	public int getElementSeq() {
		return elementSeq;
	}

	public TaskReturnedMessage setElementSeq(int elementSeq) {
		this.elementSeq = elementSeq;
		return this;
	}

	public String getReason() {
		return reason;
	}

	public TaskReturnedMessage setReason(String reason) {
		this.reason = reason;
		return this;
	}
}
