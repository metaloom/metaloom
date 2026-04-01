package io.metaloom.loom.rest.builder;

import java.util.List;
import java.util.stream.Collectors;

import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;

public interface TaskModelBuilder extends ModelBuilder, UserModelBuilder, CommentModelBuilder {

	default TaskResponse toResponse(Task task) {
		TaskResponse response = new TaskResponse();
		response.setUuid(task.getUuid());
		response.setTitle(task.getTitle());
		response.setDescription(task.getDescription());

		List<Comment> comments = daos().commentDao().loadForTask(task.getUuid());
		List<CommentResponse> restComments = comments.stream().map(this::toResponse).collect(Collectors.toList());
		response.setComments(restComments);
		setStatus(task, response);
		return response;
	}

	default TaskListResponse toTaskList(Page<Task> page) {
		return setPage(new TaskListResponse(), page, this::toResponse);
	}
}
