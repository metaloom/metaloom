package io.metaloom.loom.db.model.task;

import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface TaskDao extends CRUDDao<Task> {

	default Task createTask(User user, String title) {
		return createTask(user.getUuid(), title);
	}

	Task createTask(UUID userUuid, String title);

	List<Task> loadForAnnotation(UUID annotationUuid);

	// Asset

	Page<Task> loadPageForAsset(UUID assetUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	void assignToAsset(UUID taskUuid, UUID assetUuid);

	void unassignFromAsset(UUID taskUuid, UUID assetUuid);

	// Annotation

	Page<Task> loadPageForAnnotation(UUID annotationUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection);

	void assignToAnnotation(UUID taskUuid, UUID annotationUuid);

	void unassignFromAnnotation(UUID taskUuid, UUID annotationUuid);

	// Assignees (people, as opposed to the asset/annotation links above)

	/**
	 * Load every assignee of a task, user- and group-targeted alike, oldest assignment first.
	 */
	List<TaskAssignee> loadAssignees(UUID taskUuid);

	/**
	 * Load the assignees of several tasks in one query, so rendering a task list does not issue one lookup per row.
	 *
	 * @return every matching row; group by {@link TaskAssignee#getTaskUuid()} at the call site
	 */
	List<TaskAssignee> loadAssignees(List<UUID> taskUuids);

	/**
	 * Assign a task to a user. Idempotent — assigning twice is a no-op, not an error.
	 *
	 * @param assignerUuid who is making the assignment; may be null for a machine-made one
	 */
	void assignUser(UUID taskUuid, UUID userUuid, UUID assignerUuid);

	void unassignUser(UUID taskUuid, UUID userUuid);

	/**
	 * Assign a task to a group. Membership is resolved on read, not snapshotted, so someone joining the group inherits the task.
	 */
	void assignGroup(UUID taskUuid, UUID groupUuid, UUID assignerUuid);

	void unassignGroup(UUID taskUuid, UUID groupUuid);

	/**
	 * Every user who is currently responsible for a task: those assigned directly, plus the members of every assigned group. Deduplicated.
	 *
	 * <p>
	 * This is what notification dispatch fans out over, and it is deliberately computed rather than stored — the roster of an assigned group is a
	 * live fact.
	 * </p>
	 */
	List<UUID> loadAssignedUserUuids(UUID taskUuid);

	/**
	 * Page over the tasks a user is responsible for, whether assigned directly or through a group they belong to.
	 */
	Page<Task> loadPageAssignedTo(UUID userUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

}
