package io.metaloom.loom.rest.model.processor.workorder;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

/**
 * Result of a work order execution, reported by the processor node.
 */
public class WorkOrderResult implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("UUID of the work order this result belongs to")
	private UUID workOrderId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Status of the work order")
	private WorkOrderStatus status;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Error message if the work order failed")
	private String errorMessage;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Result data")
	private JsonObject result;

	public UUID getWorkOrderId() {
		return workOrderId;
	}

	public WorkOrderResult setWorkOrderId(UUID workOrderId) {
		this.workOrderId = workOrderId;
		return this;
	}

	public WorkOrderStatus getStatus() {
		return status;
	}

	public WorkOrderResult setStatus(WorkOrderStatus status) {
		this.status = status;
		return this;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public WorkOrderResult setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	public JsonObject getResult() {
		return result;
	}

	public WorkOrderResult setResult(JsonObject result) {
		this.result = result;
		return this;
	}
}
