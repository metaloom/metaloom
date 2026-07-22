package io.metaloom.loom.rest.model.task;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class TaskUpdateRequest extends AbstractMetaModel<TaskUpdateRequest> implements RestRequestModel {

	private String title;

	private String description;

	private TaskPriority priority;

	private TaskStatus taskStatus;

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant dueDate;

	public String getTitle() {
		return title;
	}

	public TaskUpdateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public TaskUpdateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	public TaskPriority getPriority() {
		return priority;
	}

	public TaskUpdateRequest setPriority(TaskPriority priority) {
		this.priority = priority;
		return this;
	}

	public TaskStatus getTaskStatus() {
		return taskStatus;
	}

	public TaskUpdateRequest setTaskStatus(TaskStatus taskStatus) {
		this.taskStatus = taskStatus;
		return this;
	}

	public Instant getDueDate() {
		return dueDate;
	}

	public TaskUpdateRequest setDueDate(Instant dueDate) {
		this.dueDate = dueDate;
		return this;
	}

	@Override
	public TaskUpdateRequest self() {
		return this;
	}

}
