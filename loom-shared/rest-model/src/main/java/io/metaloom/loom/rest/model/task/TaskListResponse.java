package io.metaloom.loom.rest.model.task;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class TaskListResponse extends AbstractListResponse<TaskListResponse, TaskResponse> {

	@Override
	public TaskListResponse self() {
		return this;
	}

}
