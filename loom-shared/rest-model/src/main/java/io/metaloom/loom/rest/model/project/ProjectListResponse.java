package io.metaloom.loom.rest.model.project;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ProjectListResponse extends AbstractListResponse<ProjectListResponse, ProjectResponse> {

	@Override
	public ProjectListResponse self() {
		return this;
	}

}
