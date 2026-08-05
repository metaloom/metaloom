package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_TASK;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_TASK;
import static io.metaloom.loom.db.model.perm.Permission.READ_TASK;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_TASK;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.task.TaskAssignRequest;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;
import io.metaloom.loom.rest.parameter.FilterParameters;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.parameter.SortParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class TaskEndpointService extends AbstractCRUDEndpointService<TaskDao, Task> {

	private final NotificationDispatcher notifications;

	@Inject
	public TaskEndpointService(TaskDao taskDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		NotificationDispatcher notifications) {
		super(taskDao, daos, modelBuilder, validator);
		this.notifications = notifications;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_TASK, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_TASK, modelBuilder::toTaskList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_TASK, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_TASK, () -> {
			TaskCreateRequest request = lrc.requestBody(TaskCreateRequest.class);
			validator.validate(request);

			String title = request.getTitle();
			UUID userUuid = lrc.userUuid();
			Task task = dao().createTask(userUuid, title);
			applyCreateRequest(request, task);
			return task;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		// Request-scoped, so no state leaks between concurrent updates - unlike a field on this
		// @Singleton service would.
		AtomicReference<TaskStatus> previousStatus = new AtomicReference<>();
		update(lrc, UPDATE_TASK, () -> {
			TaskUpdateRequest request = lrc.requestBody(TaskUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Task task = dao().load(id);
			// Captured before the request is applied - afterwards there is nothing left to
			// compare against, and a status change is exactly what we want to report.
			previousStatus.set(task.getStatus());
			applyUpdateRequest(request, task);
			setEditor(task, userUuid);
			return task;
		}, modelBuilder::toResponse, task -> {
			TaskStatus before = previousStatus.get();
			if (before != null && task.getStatus() != null && !before.equals(task.getStatus())) {
				notifications.taskStatusChanged(lrc.userUuid(), task, before, task.getStatus());
			}
		});
	}

	private void applyCreateRequest(TaskCreateRequest request, Task task) {
		super.update(request::getMeta, task::setMeta);
		super.update(request::getDescription, task::setDescription);
		super.update(request::getPriority, task::setPriority);
		super.update(request::getTaskStatus, task::setStatus);
		super.update(request::getDueDate, task::setDueDate);
	}

	private void applyUpdateRequest(TaskUpdateRequest request, Task task) {
		super.update(request::getMeta, task::setMeta);
		super.update(request::getTitle, task::setTitle);
		super.update(request::getDescription, task::setDescription);
		super.update(request::getPriority, task::setPriority);
		super.update(request::getTaskStatus, task::setStatus);
		super.update(request::getDueDate, task::setDueDate);
	}

	// ASSET

	public void listAssetTasks(LoomRoutingContext lrc, AssetId assetId) {
		checkPerm(lrc, READ_TASK, () -> {
			Asset asset = loadAsset(assetId);
			PagingParameters pagingParameters = lrc.pagingParams();
			FilterParameters filterParameters = lrc.filterParams();
			SortParameters sortParameters = lrc.sortParams();
			Page<Task> page = dao().loadPageForAsset(asset.getUuid(), pagingParameters.from(), pagingParameters.limit(),
				filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			TaskListResponse response = modelBuilder.toTaskList(page);
			lrc.send(response);
		});
	}

	public void assignAssetTask(LoomRoutingContext lrc, AssetId assetId, UUID taskUuid) {
		checkPerm(lrc, UPDATE_TASK, () -> {
			Asset asset = loadAsset(assetId);
			Task task = loadTask(taskUuid);
			dao().assignToAsset(task.getUuid(), asset.getUuid());
			lrc.send(modelBuilder.toResponse(task), 201);
		});
	}

	public void unassignAssetTask(LoomRoutingContext lrc, AssetId assetId, UUID taskUuid) {
		checkPerm(lrc, UPDATE_TASK, () -> {
			Asset asset = loadAsset(assetId);
			Task task = loadTask(taskUuid);
			dao().unassignFromAsset(task.getUuid(), asset.getUuid());
			lrc.sendNoContent();
		});
	}

	// ANNOTATION

	public void listAnnotationTasks(LoomRoutingContext lrc, UUID annotationUuid) {
		checkPerm(lrc, READ_TASK, () -> {
			Annotation annotation = loadAnnotation(annotationUuid);
			PagingParameters pagingParameters = lrc.pagingParams();
			FilterParameters filterParameters = lrc.filterParams();
			SortParameters sortParameters = lrc.sortParams();
			Page<Task> page = dao().loadPageForAnnotation(annotation.getUuid(), pagingParameters.from(), pagingParameters.limit(),
				filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			TaskListResponse response = modelBuilder.toTaskList(page);
			lrc.send(response);
		});
	}

	public void assignAnnotationTask(LoomRoutingContext lrc, UUID annotationUuid, UUID taskUuid) {
		checkPerm(lrc, UPDATE_TASK, () -> {
			Annotation annotation = loadAnnotation(annotationUuid);
			Task task = loadTask(taskUuid);
			dao().assignToAnnotation(task.getUuid(), annotation.getUuid());
			lrc.send(modelBuilder.toResponse(task), 201);
		});
	}

	public void unassignAnnotationTask(LoomRoutingContext lrc, UUID annotationUuid, UUID taskUuid) {
		checkPerm(lrc, UPDATE_TASK, () -> {
			Annotation annotation = loadAnnotation(annotationUuid);
			Task task = loadTask(taskUuid);
			dao().unassignFromAnnotation(task.getUuid(), annotation.getUuid());
			lrc.sendNoContent();
		});
	}

	// ASSIGNEES

	public void listAssignees(LoomRoutingContext lrc, UUID taskUuid) {
		checkPerm(lrc, READ_TASK, () -> {
			Task task = loadTask(taskUuid);
			lrc.send(modelBuilder.toTaskAssigneeList(dao().loadAssignees(task.getUuid())));
		});
	}

	public void assign(LoomRoutingContext lrc, UUID taskUuid) {
		checkPerm(lrc, UPDATE_TASK, () -> {
			TaskAssignRequest request = lrc.requestBody(TaskAssignRequest.class);
			validator.validate(request);

			Task task = loadTask(taskUuid);
			UUID actorUuid = lrc.userUuid();

			// Resolve every target BEFORE writing any of them: a request naming one good and one
			// unknown user must fail whole, not leave half an assignment behind.
			List<UUID> userUuids = nullSafe(request.getUserUuids());
			List<UUID> groupUuids = nullSafe(request.getGroupUuids());
			userUuids.forEach(this::requireUser);
			groupUuids.forEach(this::requireGroup);

			userUuids.forEach(userUuid -> dao().assignUser(task.getUuid(), userUuid, actorUuid));
			groupUuids.forEach(groupUuid -> dao().assignGroup(task.getUuid(), groupUuid, actorUuid));

			// Only the targets named in THIS request are notified. Re-assigning somebody who is
			// already an assignee is a DAO no-op and must not re-notify them either — but that is
			// a known, accepted imprecision here: the request names them, so they are told again.
			notifications.taskAssigned(actorUuid, task, userUuids, groupUuids);

			lrc.send(modelBuilder.toTaskAssigneeList(dao().loadAssignees(task.getUuid())), 201);
		});
	}

	public void unassignUser(LoomRoutingContext lrc, UUID taskUuid, UUID userUuid) {
		checkPerm(lrc, UPDATE_TASK, () -> {
			Task task = loadTask(taskUuid);
			dao().unassignUser(task.getUuid(), userUuid);
			notifications.taskUnassigned(lrc.userUuid(), task, List.of(userUuid), List.of());
			lrc.sendNoContent();
		});
	}

	public void unassignGroup(LoomRoutingContext lrc, UUID taskUuid, UUID groupUuid) {
		checkPerm(lrc, UPDATE_TASK, () -> {
			Task task = loadTask(taskUuid);
			// Resolved BEFORE the row is removed: afterwards the group is no longer an assignee
			// and the members could not be found through the task any more.
			List<UUID> members = daos().groupDao().loadUsersForGroup(groupUuid).stream().map(u -> u.getUuid()).toList();
			dao().unassignGroup(task.getUuid(), groupUuid);
			notifications.taskUnassigned(lrc.userUuid(), task, members, List.of());
			lrc.sendNoContent();
		});
	}

	private static List<UUID> nullSafe(List<UUID> uuids) {
		return uuids == null ? List.of() : uuids;
	}

	private void requireUser(UUID userUuid) {
		if (daos().userDao().load(userUuid) == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "User not found " + userUuid);
		}
	}

	private void requireGroup(UUID groupUuid) {
		if (daos().groupDao().load(groupUuid) == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Group not found " + groupUuid);
		}
	}

	private Asset loadAsset(AssetId assetId) {
		Asset asset = daos().assetDao().loadById(assetId);
		if (asset == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found " + assetId);
		}
		return asset;
	}

	private Annotation loadAnnotation(UUID annotationUuid) {
		Annotation annotation = daos().annotationDao().load(annotationUuid);
		if (annotation == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Annotation not found " + annotationUuid);
		}
		return annotation;
	}

	private Task loadTask(UUID taskUuid) {
		Task task = dao().load(taskUuid);
		if (task == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Task not found " + taskUuid);
		}
		return task;
	}
}
