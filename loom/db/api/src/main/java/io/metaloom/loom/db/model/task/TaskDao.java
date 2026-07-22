package io.metaloom.loom.db.model.task;

import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface TaskDao extends CRUDDao<Task> {

	default Task createTask(User user, String title) {
		return createTask(user.getUuid(), title);
	}

	Task createTask(UUID userUuid, String title);

	List<Task> loadForAnnotation(UUID annotationUuid);

	// Asset

	Page<Task> loadPageForAsset(UUID assetUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	void assignToAsset(UUID taskUuid, UUID assetUuid);

	void unassignFromAsset(UUID taskUuid, UUID assetUuid);

	// Annotation

	Page<Task> loadPageForAnnotation(UUID annotationUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection);

	void assignToAnnotation(UUID taskUuid, UUID annotationUuid);

	void unassignFromAnnotation(UUID taskUuid, UUID annotationUuid);

}
