package io.metaloom.loom.rest.model.task;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class TaskResponse extends AbstractCreatorEditorRestResponse<TaskResponse> implements TaskModel<TaskResponse> {

	private String title;

	private String description;

	private TaskPriority priority;

	private TaskStatus taskStatus;

	@JsonProperty(required = false)
	@JsonPropertyDescription("ISO8601 formatted due date string.")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant dueDate;

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

	@Override
	public TaskStatus getTaskStatus() {
		return taskStatus;
	}

	@Override
	public TaskResponse setTaskStatus(TaskStatus taskStatus) {
		this.taskStatus = taskStatus;
		return this;
	}

	@Override
	public Instant getDueDate() {
		return dueDate;
	}

	@Override
	public TaskResponse setDueDate(Instant dueDate) {
		this.dueDate = dueDate;
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
