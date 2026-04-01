package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PROJECT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PROJECT;
import static io.metaloom.loom.db.model.perm.Permission.READ_PROJECT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PROJECT;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.project.Project;
import io.metaloom.loom.db.model.project.ProjectDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.project.ProjectCreateRequest;
import io.metaloom.loom.rest.model.project.ProjectUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class ProjectEndpointService extends AbstractCRUDEndpointService<ProjectDao, Project> {

	@Inject
	public ProjectEndpointService(ProjectDao projectDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(projectDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_PROJECT, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_PROJECT, modelBuilder::toProjectList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_PROJECT, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_PROJECT, () -> {
			ProjectCreateRequest request = lrc.requestBody(ProjectCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String name = request.getName();
			Project project = dao().createProject(userUuid, name);
			update(request::getMeta, project::setMeta);
			return project;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_PROJECT, () -> {
			ProjectUpdateRequest request = lrc.requestBody(ProjectUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Project project = dao().load(id);
			// TOOD update
			update(request::getName, project::setName);
			setEditor(project, userUuid);
			return project;
		}, modelBuilder::toResponse);
	}

}
