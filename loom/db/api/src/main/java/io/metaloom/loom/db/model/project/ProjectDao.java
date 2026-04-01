package io.metaloom.loom.db.model.project;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface ProjectDao extends CRUDDao<Project> {

	default Project createProject(User user, String name) {
		return createProject(user.getUuid(), name);
	}

	Project createProject(UUID userUuid, String name);

}
