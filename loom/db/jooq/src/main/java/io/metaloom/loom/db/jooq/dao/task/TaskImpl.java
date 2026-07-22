package io.metaloom.loom.db.jooq.dao.task;

import java.time.Instant;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.task.Task;

public class TaskImpl extends AbstractEditableElement<Task> implements Task {

	private String title;

	private String description;

	private TaskPriority priority;

	private TaskStatus status;

	private Instant dueDate;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public Task setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Task setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public TaskPriority getPriority() {
		return priority;
	}

	@Override
	public Task setPriority(TaskPriority priority) {
		this.priority = priority;
		return this;
	}

	@Override
	public TaskStatus getStatus() {
		return status;
	}

	@Override
	public Task setStatus(TaskStatus status) {
		this.status = status;
		return this;
	}

	@Override
	public Instant getDueDate() {
		return dueDate;
	}

	@Override
	public Task setDueDate(Instant dueDate) {
		this.dueDate = dueDate;
		return this;
	}
}
