package io.metaloom.loom.db.jooq.dao.pipeline;

import static io.metaloom.loom.db.jooq.tables.JooqPipelineNodeTask.PIPELINE_NODE_TASK;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqPipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class PipelineNodeTaskDaoImpl extends AbstractJooqDao<PipelineNodeTask> implements PipelineNodeTaskDao {

	private static final NodeTaskState STATE_RUNNING = NodeTaskState.RUNNING;

	@Inject
	public PipelineNodeTaskDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Pipeline Node Tasks";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqPipelineNodeTask.PIPELINE_NODE_TASK;
	}

	@Override
	protected Class<? extends PipelineNodeTask> getPojoClass() {
		return PipelineNodeTaskImpl.class;
	}

	@Override
	public PipelineNodeTask createNodeTask(UUID userUuid, UUID itemUuid, UUID runUuid, String nodeId, String nodeKind) {
		PipelineNodeTask task = new PipelineNodeTaskImpl();
		task.setItemUuid(itemUuid);
		task.setRunUuid(runUuid);
		task.setNodeId(nodeId);
		task.setNodeKind(nodeKind);
		setCreatorEditor(task, userUuid);
		return task;
	}

	@Override
	public List<PipelineNodeTask> loadByItem(UUID itemUuid) {
		return ctx().selectFrom(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.ITEM_UUID.eq(itemUuid))
			.orderBy(PIPELINE_NODE_TASK.CREATED.asc())
			.fetchInto(getPojoClass());
	}

	@Override
	public List<PipelineNodeTask> loadByRun(UUID runUuid) {
		return ctx().selectFrom(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.RUN_UUID.eq(runUuid))
			.orderBy(PIPELINE_NODE_TASK.CREATED.asc())
			.fetchInto(getPojoClass());
	}

	@Override
	public PipelineNodeTask loadByItemAndNode(UUID itemUuid, String nodeId, int elementSeq) {
		// An execution may have been run several times, so this is a "latest" query rather than a
		// unique lookup. Without the ordering it would return an arbitrary generation and settling a
		// re-executed task would land on whichever row the planner happened to hand back.
		return ctx().selectFrom(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.ITEM_UUID.eq(itemUuid))
			.and(PIPELINE_NODE_TASK.NODE_ID.eq(nodeId))
			.and(PIPELINE_NODE_TASK.ELEMENT_SEQ.eq(elementSeq))
			.orderBy(PIPELINE_NODE_TASK.GENERATION.desc())
			.limit(1)
			.fetchOptionalInto(getPojoClass())
			.orElse(null);
	}

	@Override
	public PipelineNodeTask loadByItemAndNode(UUID itemUuid, String nodeId, int elementSeq, int generation) {
		return ctx().selectFrom(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.ITEM_UUID.eq(itemUuid))
			.and(PIPELINE_NODE_TASK.NODE_ID.eq(nodeId))
			.and(PIPELINE_NODE_TASK.ELEMENT_SEQ.eq(elementSeq))
			.and(PIPELINE_NODE_TASK.GENERATION.eq(generation))
			.fetchOptionalInto(getPojoClass())
			.orElse(null);
	}

	@Override
	public List<PipelineNodeTask> loadExpiredLeases(Instant now, int limit) {
		return ctx().selectFrom(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.STATE.eq(STATE_RUNNING))
			.and(PIPELINE_NODE_TASK.LEASE_EXPIRES_AT.isNotNull())
			.and(PIPELINE_NODE_TASK.LEASE_EXPIRES_AT.lt(toLocal(now)))
			.orderBy(PIPELINE_NODE_TASK.LEASE_EXPIRES_AT.asc())
			.limit(limit)
			.fetchInto(getPojoClass());
	}

	@Override
	public long countByRunAndState(UUID runUuid, NodeTaskState state) {
		return ctx().fetchCount(PIPELINE_NODE_TASK,
			PIPELINE_NODE_TASK.RUN_UUID.eq(runUuid).and(PIPELINE_NODE_TASK.STATE.eq(state)));
	}

	@Override
	public List<PipelineNodeTask> loadLeasedBy(String processorNodeId, int limit) {
		return ctx().selectFrom(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.STATE.eq(STATE_RUNNING))
			.and(PIPELINE_NODE_TASK.LEASED_BY.eq(processorNodeId))
			// Oldest lease first, so a truncated sweep reclaims the work that has been
			// stranded longest rather than an arbitrary slice of it.
			.orderBy(PIPELINE_NODE_TASK.LEASE_EXPIRES_AT.asc())
			.limit(limit)
			.fetchInto(getPojoClass());
	}

	@Override
	public long countLeasedBy(String processorNodeId) {
		return ctx().fetchCount(PIPELINE_NODE_TASK,
			PIPELINE_NODE_TASK.STATE.eq(STATE_RUNNING).and(PIPELINE_NODE_TASK.LEASED_BY.eq(processorNodeId)));
	}

	@Override
	public Page<PipelineNodeTask> loadPageByRun(UUID runUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx().selectFrom(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.RUN_UUID.eq(runUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.STATUS) {
			return query.and(PIPELINE_NODE_TASK.STATE.eq(parseState(filter.valueStr())));
		}
		return super.applyFilter(query, filter);
	}

	/**
	 * A caller filtering on a state that does not exist has made a bad request, not found nothing.
	 * Returning an empty page instead would hide the typo behind a plausible answer.
	 */
	private static NodeTaskState parseState(String value) {
		try {
			return NodeTaskState.parse("pipeline_node_task.state", value);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, e.getMessage());
		}
	}

	/**
	 * The lease columns are {@code TIMESTAMP WITHOUT TIME ZONE}, so an Instant has to
	 * be pinned to UTC rather than to the JVM default - otherwise a Loom running in a
	 * non-UTC zone would reap leases at the wrong moment.
	 */
	private static LocalDateTime toLocal(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
