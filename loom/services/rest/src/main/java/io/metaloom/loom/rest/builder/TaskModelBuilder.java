package io.metaloom.loom.rest.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskAssignee;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.task.TaskAssigneeListResponse;
import io.metaloom.loom.rest.model.task.TaskAssigneeResponse;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;

public interface TaskModelBuilder extends ModelBuilder, UserModelBuilder, CommentModelBuilder {

	default TaskResponse toResponse(Task task) {
		return toResponse(task, daos().taskDao().loadAssignees(task.getUuid()), new HashMap<>());
	}

	/**
	 * Render a task, taking its assignees from an already-loaded list rather than querying for them.
	 *
	 * @param assignees   the assignees of <b>this</b> task
	 * @param nameCache   memoises user/group name lookups across a whole page; pass a fresh map for a single response
	 */
	private TaskResponse toResponse(Task task, List<TaskAssignee> assignees, Map<UUID, String> nameCache) {
		TaskResponse response = new TaskResponse();
		response.setUuid(task.getUuid());
		response.setTitle(task.getTitle());
		response.setDescription(task.getDescription());
		response.setPriority(task.getPriority());
		response.setTaskStatus(task.getStatus());
		response.setDueDate(task.getDueDate());

		List<Comment> comments = daos().commentDao().loadForTask(task.getUuid());
		List<CommentResponse> restComments = comments.stream().map(this::toResponse).collect(Collectors.toList());
		response.setComments(restComments);
		response.setAssignees(toAssigneeResponses(assignees, nameCache));
		setStatus(task, response);
		return response;
	}

	default TaskListResponse toTaskList(Page<Task> page) {
		// Load every assignee of every task on the page in ONE query, then hand each task its own
		// slice. Doing it per row would add a query per task on a route that already pays one for
		// comments.
		List<Task> tasks = new ArrayList<>();
		page.forEach(tasks::add);
		List<UUID> taskUuids = tasks.stream().map(Task::getUuid).collect(Collectors.toList());
		Map<UUID, List<TaskAssignee>> byTask = daos().taskDao().loadAssignees(taskUuids).stream()
			.collect(Collectors.groupingBy(TaskAssignee::getTaskUuid));
		Map<UUID, String> nameCache = new HashMap<>();

		return setPage(new TaskListResponse(), page,
			task -> toResponse(task, byTask.getOrDefault(task.getUuid(), List.of()), nameCache));
	}

	default TaskAssigneeListResponse toTaskAssigneeList(List<TaskAssignee> assignees) {
		TaskAssigneeListResponse response = new TaskAssigneeListResponse();
		// Not a paged resource: the assignees of one task are a small bounded set, so this carries
		// no PagingInfo and setPage does not apply (TaskAssignee is not an Element - it has no uuid).
		toAssigneeResponses(assignees, new HashMap<>()).forEach(response::add);
		return response;
	}

	private List<TaskAssigneeResponse> toAssigneeResponses(List<TaskAssignee> assignees, Map<UUID, String> nameCache) {
		return assignees.stream().map(assignee -> {
			TaskAssigneeResponse item = new TaskAssigneeResponse();
			item.setAssigned(assignee.getAssigned());
			item.setAssignerUuid(assignee.getAssignerUuid());
			if (assignee.isGroupAssignment()) {
				item.setGroupUuid(assignee.getGroupUuid());
				item.setName(nameCache.computeIfAbsent(assignee.getGroupUuid(), uuid -> {
					Group group = daos().groupDao().load(uuid);
					return group == null ? null : group.getName();
				}));
			} else {
				item.setUserUuid(assignee.getUserUuid());
				item.setName(nameCache.computeIfAbsent(assignee.getUserUuid(), uuid -> {
					User user = daos().userDao().load(uuid);
					return user == null ? null : user.getUsername();
				}));
			}
			return item;
		}).collect(Collectors.toList());
	}
}
