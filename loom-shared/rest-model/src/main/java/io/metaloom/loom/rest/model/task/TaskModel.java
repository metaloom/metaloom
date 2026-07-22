package io.metaloom.loom.rest.model.task;

import java.time.Instant;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface TaskModel<T extends TaskModel<T>> extends MetaModel<T>, RestModel {

	String getTitle();

	T setTitle(String title);

	String getDescription();

	T setDescription(String description);

	TaskPriority getPriority();

	T setPriority(TaskPriority priority);

	TaskStatus getTaskStatus();

	T setTaskStatus(TaskStatus taskStatus);

	Instant getDueDate();

	T setDueDate(Instant dueDate);

}
