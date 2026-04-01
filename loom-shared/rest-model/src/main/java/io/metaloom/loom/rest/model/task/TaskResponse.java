package io.metaloom.loom.rest.model.task;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class TaskResponse extends AbstractCreatorEditorRestResponse<TaskResponse> implements TaskModel<TaskResponse> {

	private String title;

	private String description;

	private TaskPriority priority;

	private List<CommentResponse> comments = new ArrayList<>();

	// reactions

	// assignedTo

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public TaskResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public TaskResponse setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public TaskPriority getPriority() {
		return priority;
	}

	@Override
	public TaskResponse setPriority(TaskPriority priority) {
		this.priority = priority;
		return this;
	}

	public List<CommentResponse> getComments() {
		return comments;
	}

	public TaskResponse setComments(List<CommentResponse> comments) {
		this.comments = comments;
		return this;
	}

	@Override
	public TaskResponse self() {
		return this;
	}
}
