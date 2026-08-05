package io.metaloom.loom.rest.model.task;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request to assign a task to one or more users and/or groups.
 *
 * <p>
 * Additive: the listed targets are added to whatever the task already has. Removing an assignee is a DELETE on the specific sub-path, not an
 * omission here — a PUT-style "these are now the assignees" body would make a stale client silently unassign someone.
 * </p>
 */
public class TaskAssignRequest implements RestRequestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuids of the users to assign the task to.")
	private List<UUID> userUuids = new ArrayList<>();

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuids of the groups to assign the task to.")
	private List<UUID> groupUuids = new ArrayList<>();

	public List<UUID> getUserUuids() {
		return userUuids;
	}

	public TaskAssignRequest setUserUuids(List<UUID> userUuids) {
		this.userUuids = userUuids;
		return this;
	}

	public List<UUID> getGroupUuids() {
		return groupUuids;
	}

	public TaskAssignRequest setGroupUuids(List<UUID> groupUuids) {
		this.groupUuids = groupUuids;
		return this;
	}

}
