package io.metaloom.loom.rest.model.processor.workorder;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.vertx.core.json.JsonObject;

/**
 * A work order that loom dispatches to a processor node.
 */
public class WorkOrder implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Unique identifier of the work order")
	private UUID workOrderId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The type of work to perform")
	private WorkOrderType type;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Required capability to execute this work order")
	private ProcessorCapability requiredCapability;

	@JsonProperty(required = false)
	@JsonPropertyDescription("List of asset UUIDs to process (for FINGERPRINT type)")
	private List<UUID> assetUuids;

	@JsonProperty(required = false)
	@JsonPropertyDescription("UUID of the asset location to scan (for FILESYSTEM_SCAN type)")
	private UUID assetLocationUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional parameters for the work order")
	private JsonObject parameters;

	public UUID getWorkOrderId() {
		return workOrderId;
	}

	public WorkOrder setWorkOrderId(UUID workOrderId) {
		this.workOrderId = workOrderId;
		return this;
	}

	public WorkOrderType getType() {
		return type;
	}

	public WorkOrder setType(WorkOrderType type) {
		this.type = type;
		return this;
	}

	public ProcessorCapability getRequiredCapability() {
		return requiredCapability;
	}

	public WorkOrder setRequiredCapability(ProcessorCapability requiredCapability) {
		this.requiredCapability = requiredCapability;
		return this;
	}

	public List<UUID> getAssetUuids() {
		return assetUuids;
	}

	public WorkOrder setAssetUuids(List<UUID> assetUuids) {
		this.assetUuids = assetUuids;
		return this;
	}

	public UUID getAssetLocationUuid() {
		return assetLocationUuid;
	}

	public WorkOrder setAssetLocationUuid(UUID assetLocationUuid) {
		this.assetLocationUuid = assetLocationUuid;
		return this;
	}

	public JsonObject getParameters() {
		return parameters;
	}

	public WorkOrder setParameters(JsonObject parameters) {
		this.parameters = parameters;
		return this;
	}
}
