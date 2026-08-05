package io.metaloom.loom.rest.model.task;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class TaskAssigneeListResponse extends AbstractListResponse<TaskAssigneeListResponse, TaskAssigneeResponse> {

	@Override
	public TaskAssigneeListResponse self() {
		return this;
	}

}
