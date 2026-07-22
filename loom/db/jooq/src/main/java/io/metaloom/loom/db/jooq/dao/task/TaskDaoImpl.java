package io.metaloom.loom.db.jooq.dao.task;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTask.ANNOTATION_TASK;
import static io.metaloom.loom.db.jooq.tables.JooqAssetTask.ASSET_TASK;
import static io.metaloom.loom.db.jooq.tables.JooqTask.TASK;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

}
