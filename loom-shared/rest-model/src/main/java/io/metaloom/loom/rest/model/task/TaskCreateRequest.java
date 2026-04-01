package io.metaloom.loom.rest.model.task;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class TaskCreateRequest extends AbstractMetaModel<TaskCreateRequest> implements TaskModel<TaskCreateRequest>, RestRequestModel {

	private String title;

	private String description;

	private TaskPriority priority;

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
	public TaskCreateRequest self() {
		return this;
	}

}
