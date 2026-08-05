package io.metaloom.loom.rest.model.task;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One assignment of a task to a user or to a group.
 *
 * <p>
 * Exactly one of {@code userUuid} and {@code groupUuid} is populated. The accompanying {@code name} is denormalised onto the response so a client can
 * render an assignee chip without a second lookup per row.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskAssigneeResponse implements RestResponseModel<TaskAssigneeResponse> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the assigned user. Null when this entry assigns to a group.")
	private UUID userUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the assigned group. Null when this entry assigns to a user.")
	private UUID groupUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Display name of the assigned user or group.")
	private String name;

	@JsonProperty(required = false)
	@JsonPropertyDescription("ISO8601 formatted timestamp of when the assignment was made.")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant assigned;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the user who made the assignment. Null when that user has since been deleted.")
	private UUID assignerUuid;

	public UUID getUserUuid() {
		return userUuid;
	}

	public TaskAssigneeResponse setUserUuid(UUID userUuid) {
		this.userUuid = userUuid;
		return this;
	}

	public UUID getGroupUuid() {
		return groupUuid;
	}

	public TaskAssigneeResponse setGroupUuid(UUID groupUuid) {
		this.groupUuid = groupUuid;
		return this;
	}

	public String getName() {
		return name;
	}

	public TaskAssigneeResponse setName(String name) {
		this.name = name;
		return this;
	}

	public Instant getAssigned() {
		return assigned;
	}

	public TaskAssigneeResponse setAssigned(Instant assigned) {
		this.assigned = assigned;
		return this;
	}

	public UUID getAssignerUuid() {
		return assignerUuid;
	}

	public TaskAssigneeResponse setAssignerUuid(UUID assignerUuid) {
		this.assignerUuid = assignerUuid;
		return this;
	}

	@Override
	public TaskAssigneeResponse self() {
		return this;
	}

}
