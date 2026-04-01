package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.project.Project;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.project.ProjectListResponse;
import io.metaloom.loom.rest.model.project.ProjectResponse;

public interface ProjectModelBuilder extends ModelBuilder, UserModelBuilder {

	default ProjectResponse toResponse(Project project) {
		ProjectResponse response = new ProjectResponse();
		response.setUuid(project.getUuid());
		response.setName(project.getName());
		setStatus(project, response);
		return response;
	}

	default ProjectListResponse toProjectList(Page<Project> page) {
		return setPage(new ProjectListResponse(), page, this::toResponse);
	}

}
