package io.metaloom.loom.rest.model.task;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class TaskCreateRequest extends AbstractMetaModel<TaskCreateRequest> implements TaskModel<TaskCreateRequest>, RestRequestModel {

	private String title;

	private String description;

	private TaskPriority priority;

	private TaskStatus taskStatus;

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant dueDate;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public TaskCreateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public TaskCreateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public TaskPriority getPriority() {
		return priority;
	}

	@Override
	public TaskCreateRequest setPriority(TaskPriority priority) {
		this.priority = priority;
		return this;
	}

	@Override
	public TaskStatus getTaskStatus() {
		return taskStatus;
	}

	@Override
	public TaskCreateRequest setTaskStatus(TaskStatus taskStatus) {
		this.taskStatus = taskStatus;
		return this;
	}

	@Override
	public Instant getDueDate() {
		return dueDate;
	}

	@Override
	public TaskCreateRequest setDueDate(Instant dueDate) {
		this.dueDate = dueDate;
		return this;
	}

	@Override
	public TaskCreateRequest self() {
		return this;
	}

}
