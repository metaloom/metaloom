package io.metaloom.loom.db.model.task;

import java.time.Instant;
import java.util.UUID;

/**
 * One assignment of a task to a user or to a group.
 *
 * <p>
 * Deliberately <b>not</b> an {@code Element}: the {@code task_assignee} row has no uuid of its own, so it can neither be loaded nor updated by id.
 * It is a link, created and destroyed whole through {@link TaskDao#assignUser(UUID, UUID, UUID)} and friends, exactly like the {@code asset_task}
 * row behind {@link TaskDao#assignToAsset(UUID, UUID)}.
 * </p>
 *
 * <p>
 * Exactly one of {@link #getUserUuid()} and {@link #getGroupUuid()} is set; the database enforces that with a CHECK constraint. Use
 * {@link #isGroupAssignment()} rather than null-testing at call sites.
 * </p>
 */
public interface TaskAssignee {

	UUID getTaskUuid();

	TaskAssignee setTaskUuid(UUID taskUuid);

	/**
	 * The assigned user, or null when this row assigns to a group.
	 */
	UUID getUserUuid();

	TaskAssignee setUserUuid(UUID userUuid);

	/**
	 * The assigned group, or null when this row assigns to a user.
	 */
	UUID getGroupUuid();

	TaskAssignee setGroupUuid(UUID groupUuid);

	Instant getAssigned();

	TaskAssignee setAssigned(Instant assigned);

	/**
	 * Who made the assignment. Nullable — the assigner may since have been deleted.
	 */
	UUID getAssignerUuid();

	TaskAssignee setAssignerUuid(UUID assignerUuid);

	/**
	 * Return true when this row assigns to a group rather than to a single user.
	 */
	default boolean isGroupAssignment() {
		return getGroupUuid() != null;
	}

}
