package io.metaloom.loom.db.model.task;

import java.time.Instant;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.db.CUDElement;

public interface Task extends CUDElement<Task> {

	String getTitle();

	Task setTitle(String title);

	String getDescription();

	Task setDescription(String description);

	TaskPriority getPriority();

	Task setPriority(TaskPriority priority);

	TaskStatus getStatus();

	Task setStatus(TaskStatus status);

	Instant getDueDate();

	Task setDueDate(Instant dueDate);

}
