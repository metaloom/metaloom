package io.metaloom.loom.db.model.task;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface TaskDao extends CRUDDao<Task> {

	default Task createTask(User user, String title) {
		return createTask(user.getUuid(), title);
	}

	Task createTask(UUID userUuid, String title);

	List<Task> loadForAnnotation(UUID annotationUuid);

}
