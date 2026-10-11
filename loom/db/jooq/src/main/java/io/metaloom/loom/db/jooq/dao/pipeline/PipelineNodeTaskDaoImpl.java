package io.metaloom.loom.db.jooq.dao.pipeline;

import static io.metaloom.loom.db.jooq.tables.JooqPipelineNodeTask.PIPELINE_NODE_TASK;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

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

	// --- Performance aggregation -------------------------------------------------------------------------------------------------------------------

	/** A reuse handed on a result instead of computing it. */
	private static Condition reused() {
		return PIPELINE_NODE_TASK.STATE.eq(NodeTaskState.COMPLETED).and(PIPELINE_NODE_TASK.ORIGIN.in("LOCAL", "REMOTE"));
	}

	/** A completed execution that did the work. NULL origin predates V2.117 and reads as COMPUTED, as on the wire. */
	private static Condition computed() {
		return PIPELINE_NODE_TASK.STATE.eq(NodeTaskState.COMPLETED)
			.and(PIPELINE_NODE_TASK.ORIGIN.isNull().or(PIPELINE_NODE_TASK.ORIGIN.eq("COMPUTED")));
	}

	/** Only executions that settled; a pending or running row has no duration yet. */
	private static Condition settledIn(List<UUID> runUuids) {
		return PIPELINE_NODE_TASK.RUN_UUID.in(runUuids)
			.and(PIPELINE_NODE_TASK.STATE.in(NodeTaskState.COMPLETED, NodeTaskState.FAILED, NodeTaskState.SKIPPED));
	}

	private static Field<Long> duration() {
		return DSL.coalesce(PIPELINE_NODE_TASK.DURATION_MS, 0L);
	}

	private static Field<Long> countWhere(Condition condition) {
		return DSL.count().filterWhere(condition).cast(SQLDataType.BIGINT);
	}

	@Override
	public List<NodeTimeStats> aggregateByNode(List<UUID> runUuids) {
		if (runUuids == null || runUuids.isEmpty()) {
			return List.of();
		}
		// Rows settled without a dispatch (a skip, the source node) are written with the placeholder kind "skipped"; the real kind is whatever
		// the dispatched rows of the same node say.
		Field<String> kind = DSL.max(DSL.when(PIPELINE_NODE_TASK.NODE_KIND.ne("skipped"), PIPELINE_NODE_TASK.NODE_KIND)).as("kind");
		Field<Long> tasks = DSL.count().cast(SQLDataType.BIGINT).as("tasks");
		Field<Long> computedCount = countWhere(computed()).as("computed_count");
		Field<Long> reusedCount = countWhere(reused()).as("reused_count");
		Field<Long> failed = countWhere(PIPELINE_NODE_TASK.STATE.eq(NodeTaskState.FAILED)).as("failed");
		Field<Long> skipped = countWhere(PIPELINE_NODE_TASK.STATE.eq(NodeTaskState.SKIPPED)).as("skipped");
		Field<Long> totalMs = DSL.sum(duration()).cast(SQLDataType.BIGINT).as("total_ms");
		Field<Long> computedMs = DSL.coalesce(DSL.sum(duration()).filterWhere(computed()), DSL.inline(java.math.BigDecimal.ZERO))
			.cast(SQLDataType.BIGINT).as("computed_ms");
		Field<Long> p50 = DSL.percentileCont(0.5).withinGroupOrderBy(PIPELINE_NODE_TASK.DURATION_MS).filterWhere(computed())
			.cast(SQLDataType.BIGINT).as("p50_ms");
		Field<Long> p95 = DSL.percentileCont(0.95).withinGroupOrderBy(PIPELINE_NODE_TASK.DURATION_MS).filterWhere(computed())
			.cast(SQLDataType.BIGINT).as("p95_ms");
		Field<Long> max = DSL.max(PIPELINE_NODE_TASK.DURATION_MS).filterWhere(computed()).as("max_ms");
		return ctx().select(PIPELINE_NODE_TASK.NODE_ID, kind, tasks, computedCount, reusedCount, failed, skipped, totalMs, computedMs, p50, p95, max)
			.from(PIPELINE_NODE_TASK)
			.where(settledIn(runUuids))
			.groupBy(PIPELINE_NODE_TASK.NODE_ID)
			.orderBy(totalMs.desc(), PIPELINE_NODE_TASK.NODE_ID)
			.fetch(r -> new NodeTimeStats(r.get(PIPELINE_NODE_TASK.NODE_ID), r.get(kind), r.get(tasks), r.get(computedCount), r.get(reusedCount),
				r.get(failed), r.get(skipped), r.get(totalMs) == null ? 0 : r.get(totalMs), r.get(computedMs), r.get(p50), r.get(p95), r.get(max)));
	}

	@Override
	public List<DurationBucket> durationHistogram(List<UUID> runUuids) {
		if (runUuids == null || runUuids.isEmpty()) {
			return List.of();
		}
		// floor(log2(ms)): 0-1 ms -> 0, 2-3 -> 1, 4-7 -> 2, ... Power-of-two buckets because node durations span six orders of magnitude, from a
		// hash in microseconds to a transcription in minutes; linear buckets would put everything in the first one.
		Field<Integer> bucket = DSL.field("floor(log(2, greatest({0}, 1)::numeric))::int", Integer.class, PIPELINE_NODE_TASK.DURATION_MS).as("bucket");
		Field<Long> count = DSL.count().cast(SQLDataType.BIGINT).as("count");
		return ctx().select(PIPELINE_NODE_TASK.NODE_ID, bucket, count)
			.from(PIPELINE_NODE_TASK)
			.where(PIPELINE_NODE_TASK.RUN_UUID.in(runUuids).and(computed()).and(PIPELINE_NODE_TASK.DURATION_MS.isNotNull()))
			.groupBy(PIPELINE_NODE_TASK.NODE_ID, DSL.field("bucket"))
			.orderBy(PIPELINE_NODE_TASK.NODE_ID, DSL.field("bucket"))
			.fetch(r -> new DurationBucket(r.get(PIPELINE_NODE_TASK.NODE_ID), r.get(bucket), r.get(count)));
	}

	@Override
	public List<RunNodeTotal> totalsByRunAndNode(List<UUID> runUuids) {
		if (runUuids == null || runUuids.isEmpty()) {
			return List.of();
		}
		Field<Long> tasks = DSL.count().cast(SQLDataType.BIGINT).as("tasks");
		Field<Long> reusedCount = countWhere(reused()).as("reused_count");
		Field<Long> totalMs = DSL.sum(duration()).cast(SQLDataType.BIGINT).as("total_ms");
		return ctx().select(PIPELINE_NODE_TASK.RUN_UUID, PIPELINE_NODE_TASK.NODE_ID, tasks, reusedCount, totalMs)
			.from(PIPELINE_NODE_TASK)
			.where(settledIn(runUuids))
			.groupBy(PIPELINE_NODE_TASK.RUN_UUID, PIPELINE_NODE_TASK.NODE_ID)
			.fetch(r -> new RunNodeTotal(r.get(PIPELINE_NODE_TASK.RUN_UUID), r.get(PIPELINE_NODE_TASK.NODE_ID), r.get(tasks), r.get(reusedCount),
				r.get(totalMs) == null ? 0 : r.get(totalMs)));
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
