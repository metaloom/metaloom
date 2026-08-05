package io.metaloom.loom.db.jooq.dao.task;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTask.ANNOTATION_TASK;
import static io.metaloom.loom.db.jooq.tables.JooqAssetTask.ASSET_TASK;
import static io.metaloom.loom.db.jooq.tables.JooqTask.TASK;
import static io.metaloom.loom.db.jooq.tables.JooqTaskAssignee.TASK_ASSIGNEE;
import static io.metaloom.loom.db.jooq.tables.JooqUserGroup.USER_GROUP;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqTask;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskAssignee;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class TaskDaoImpl extends AbstractJooqDao<Task> implements TaskDao {

	@Inject
	public TaskDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Tasks";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqTask.TASK;
	}

	@Override
	protected Class<? extends Task> getPojoClass() {
		return TaskImpl.class;
	}

	@Override
	public Task createTask(UUID userUuid, String title) {
		Task task = new TaskImpl();
		task.setTitle(title);
		task.setPriority(TaskPriority.MEDIUM);
		task.setStatus(TaskStatus.PENDING);
		setCreatorEditor(task, userUuid);
		return task;
	}

	@Override
	public List<Task> loadForAnnotation(UUID annotationUuid) {
		Objects.requireNonNull(annotationUuid, "The annotation uuid must be provided");
		return ctx().select(getTable())
			.from(getTable())
			.join(ANNOTATION_TASK)
			.on(ANNOTATION_TASK.TASK_UUID.eq(TASK.UUID))
			.where(ANNOTATION_TASK.ANNOTATION_UUID.eq(annotationUuid))
			.fetchInto(getPojoClass());
	}

	@Override
	public Page<Task> loadPageForAsset(UUID assetUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		Objects.requireNonNull(assetUuid, "The asset uuid must be provided");
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.join(ASSET_TASK)
			.on(ASSET_TASK.TASK_UUID.eq(TASK.UUID))
			.where(ASSET_TASK.ASSET_UUID.eq(assetUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public void assignToAsset(UUID taskUuid, UUID assetUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(assetUuid, "The asset uuid must be provided");
		ctx()
			.insertInto(ASSET_TASK, ASSET_TASK.ASSET_UUID, ASSET_TASK.TASK_UUID)
			.values(assetUuid, taskUuid)
			.onConflict(ASSET_TASK.ASSET_UUID, ASSET_TASK.TASK_UUID)
			.doNothing()
			.execute();
	}

	@Override
	public void unassignFromAsset(UUID taskUuid, UUID assetUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(assetUuid, "The asset uuid must be provided");
		ctx()
			.deleteFrom(ASSET_TASK)
			.where(ASSET_TASK.ASSET_UUID.eq(assetUuid).and(ASSET_TASK.TASK_UUID.eq(taskUuid)))
			.execute();
	}

	@Override
	public Page<Task> loadPageForAnnotation(UUID annotationUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		Objects.requireNonNull(annotationUuid, "The annotation uuid must be provided");
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.join(ANNOTATION_TASK)
			.on(ANNOTATION_TASK.TASK_UUID.eq(TASK.UUID))
			.where(ANNOTATION_TASK.ANNOTATION_UUID.eq(annotationUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public void assignToAnnotation(UUID taskUuid, UUID annotationUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(annotationUuid, "The annotation uuid must be provided");
		ctx()
			.insertInto(ANNOTATION_TASK, ANNOTATION_TASK.ANNOTATION_UUID, ANNOTATION_TASK.TASK_UUID)
			.values(annotationUuid, taskUuid)
			.onConflict(ANNOTATION_TASK.ANNOTATION_UUID, ANNOTATION_TASK.TASK_UUID)
			.doNothing()
			.execute();
	}

	@Override
	public void unassignFromAnnotation(UUID taskUuid, UUID annotationUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(annotationUuid, "The annotation uuid must be provided");
		ctx()
			.deleteFrom(ANNOTATION_TASK)
			.where(ANNOTATION_TASK.ANNOTATION_UUID.eq(annotationUuid).and(ANNOTATION_TASK.TASK_UUID.eq(taskUuid)))
			.execute();
	}

	// Assignees

	@Override
	public List<TaskAssignee> loadAssignees(UUID taskUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		return ctx()
			.selectFrom(TASK_ASSIGNEE)
			.where(TASK_ASSIGNEE.TASK_UUID.eq(taskUuid))
			.orderBy(TASK_ASSIGNEE.ASSIGNED.asc())
			.fetchInto(TaskAssigneeImpl.class)
			.stream()
			.map(TaskAssignee.class::cast)
			.collect(Collectors.toList());
	}

	@Override
	public List<TaskAssignee> loadAssignees(List<UUID> taskUuids) {
		Objects.requireNonNull(taskUuids, "The task uuids must be provided");
		if (taskUuids.isEmpty()) {
			// An empty IN list is legal SQL but a pointless round trip.
			return List.of();
		}
		return ctx()
			.selectFrom(TASK_ASSIGNEE)
			.where(TASK_ASSIGNEE.TASK_UUID.in(taskUuids))
			.orderBy(TASK_ASSIGNEE.ASSIGNED.asc())
			.fetchInto(TaskAssigneeImpl.class)
			.stream()
			.map(TaskAssignee.class::cast)
			.collect(Collectors.toList());
	}

	@Override
	public void assignUser(UUID taskUuid, UUID userUuid, UUID assignerUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		// The conflict target is the PARTIAL unique index task_assignee_user_key, so the
		// predicate has to be restated here - Postgres matches a partial index only when the
		// statement repeats its WHERE clause.
		ctx()
			.insertInto(TASK_ASSIGNEE, TASK_ASSIGNEE.TASK_UUID, TASK_ASSIGNEE.USER_UUID, TASK_ASSIGNEE.ASSIGNER_UUID)
			.values(taskUuid, userUuid, assignerUuid)
			.onConflict(TASK_ASSIGNEE.TASK_UUID, TASK_ASSIGNEE.USER_UUID)
			.where(TASK_ASSIGNEE.USER_UUID.isNotNull())
			.doNothing()
			.execute();
	}

	@Override
	public void unassignUser(UUID taskUuid, UUID userUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		ctx()
			.deleteFrom(TASK_ASSIGNEE)
			.where(TASK_ASSIGNEE.TASK_UUID.eq(taskUuid).and(TASK_ASSIGNEE.USER_UUID.eq(userUuid)))
			.execute();
	}

	@Override
	public void assignGroup(UUID taskUuid, UUID groupUuid, UUID assignerUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(groupUuid, "The group uuid must be provided");
		ctx()
			.insertInto(TASK_ASSIGNEE, TASK_ASSIGNEE.TASK_UUID, TASK_ASSIGNEE.GROUP_UUID, TASK_ASSIGNEE.ASSIGNER_UUID)
			.values(taskUuid, groupUuid, assignerUuid)
			.onConflict(TASK_ASSIGNEE.TASK_UUID, TASK_ASSIGNEE.GROUP_UUID)
			.where(TASK_ASSIGNEE.GROUP_UUID.isNotNull())
			.doNothing()
			.execute();
	}

	@Override
	public void unassignGroup(UUID taskUuid, UUID groupUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		Objects.requireNonNull(groupUuid, "The group uuid must be provided");
		ctx()
			.deleteFrom(TASK_ASSIGNEE)
			.where(TASK_ASSIGNEE.TASK_UUID.eq(taskUuid).and(TASK_ASSIGNEE.GROUP_UUID.eq(groupUuid)))
			.execute();
	}

	@Override
	public List<UUID> loadAssignedUserUuids(UUID taskUuid) {
		Objects.requireNonNull(taskUuid, "The task uuid must be provided");
		// Directly assigned users, plus the members of every assigned group. UNION (not UNION ALL)
		// so someone who is both named directly and sits in an assigned group appears once.
		return ctx()
			.selectDistinct(TASK_ASSIGNEE.USER_UUID)
			.from(TASK_ASSIGNEE)
			.where(TASK_ASSIGNEE.TASK_UUID.eq(taskUuid).and(TASK_ASSIGNEE.USER_UUID.isNotNull()))
			.union(
				ctx().selectDistinct(USER_GROUP.USER_UUID)
					.from(TASK_ASSIGNEE)
					.join(USER_GROUP).on(USER_GROUP.GROUP_UUID.eq(TASK_ASSIGNEE.GROUP_UUID))
					.where(TASK_ASSIGNEE.TASK_UUID.eq(taskUuid)))
			.fetchInto(UUID.class);
	}

	@Override
	public Page<Task> loadPageAssignedTo(UUID userUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		// LEFT JOIN rather than JOIN on user_group: a directly assigned row has a null group_uuid
		// and would be dropped by an inner join, which would silently hide every direct assignment.
		SelectConditionStep<?> query = ctx()
			.selectDistinct(getTable().fields())
			.from(getTable())
			.join(TASK_ASSIGNEE)
			.on(TASK_ASSIGNEE.TASK_UUID.eq(TASK.UUID))
			.leftJoin(USER_GROUP)
			.on(USER_GROUP.GROUP_UUID.eq(TASK_ASSIGNEE.GROUP_UUID))
			.where(TASK_ASSIGNEE.USER_UUID.eq(userUuid).or(USER_GROUP.USER_UUID.eq(userUuid)));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

}
