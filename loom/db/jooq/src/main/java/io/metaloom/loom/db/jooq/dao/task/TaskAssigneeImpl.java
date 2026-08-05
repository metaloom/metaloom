package io.metaloom.loom.db.jooq.dao.task;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.model.task.TaskAssignee;

/**
 * Plain value holder for a {@code task_assignee} row.
 *
 * <p>
 * Extends nothing: the row has no uuid and no audit columns, so none of the {@code AbstractElement} hierarchy applies. It is populated by jOOQ's
 * {@code fetchInto} and never stored through the DAO framework.
 * </p>
 */
public class TaskAssigneeImpl implements TaskAssignee {

	private UUID taskUuid;

	private UUID userUuid;

	private UUID groupUuid;

	private Instant assigned;

	private UUID assignerUuid;

	@Override
	public UUID getTaskUuid() {
		return taskUuid;
	}

	@Override
	public TaskAssignee setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

	@Override
	public UUID getUserUuid() {
		return userUuid;
	}

	@Override
	public TaskAssignee setUserUuid(UUID userUuid) {
		this.userUuid = userUuid;
		return this;
	}

	@Override
	public UUID getGroupUuid() {
		return groupUuid;
	}

	@Override
	public TaskAssignee setGroupUuid(UUID groupUuid) {
		this.groupUuid = groupUuid;
		return this;
	}

	@Override
	public Instant getAssigned() {
		return assigned;
	}

	@Override
	public TaskAssignee setAssigned(Instant assigned) {
		this.assigned = assigned;
		return this;
	}

	@Override
	public UUID getAssignerUuid() {
		return assignerUuid;
	}

	@Override
	public TaskAssignee setAssignerUuid(UUID assignerUuid) {
		this.assignerUuid = assignerUuid;
		return this;
	}

	@Override
	public String toString() {
		return "TaskAssignee{task=" + taskUuid + ", " + (isGroupAssignment() ? "group=" + groupUuid : "user=" + userUuid) + "}";
	}

}
