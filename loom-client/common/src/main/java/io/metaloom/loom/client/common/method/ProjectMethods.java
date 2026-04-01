package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.project.ProjectCreateRequest;
import io.metaloom.loom.rest.model.project.ProjectListResponse;
import io.metaloom.loom.rest.model.project.ProjectResponse;
import io.metaloom.loom.rest.model.project.ProjectUpdateRequest;

public interface ProjectMethods {

	LoomClientRequest<ProjectResponse> loadProject(UUID uuid);

	LoomClientRequest<ProjectResponse> createProject(ProjectCreateRequest request);

	LoomClientRequest<ProjectResponse> updateProject(UUID uuid, ProjectUpdateRequest request);

	LoomClientRequest<ProjectListResponse> listProjects();

	LoomClientRequest<NoResponse> deleteProject(UUID uuid);
}
