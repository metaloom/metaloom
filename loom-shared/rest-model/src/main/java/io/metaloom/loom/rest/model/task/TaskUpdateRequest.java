package io.metaloom.loom.rest.model.task;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class TaskUpdateRequest extends AbstractMetaModel<TaskUpdateRequest> implements RestRequestModel {

	private String title;

	private String description;

	private TaskPriority priority;

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

	@Override
	public TaskUpdateRequest self() {
		return this;
	}

}
