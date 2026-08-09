package io.metaloom.loom.db.jooq.dao.pipeline;

import static io.metaloom.loom.db.jooq.tables.JooqPipelineRun.PIPELINE_RUN;
import static io.metaloom.loom.db.model.pipeline.PipelineRun.META_DEFINITION;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.DatePart;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.pipeline.PipelineRunKind;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqPipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunDayStats;
import io.vertx.core.json.JsonObject;

@Singleton
public class PipelineRunDaoImpl extends AbstractJooqDao<PipelineRun> implements PipelineRunDao {

	@Inject
	public PipelineRunDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Pipeline Runs";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqPipelineRun.PIPELINE_RUN;
	}

	@Override
	protected Class<? extends PipelineRun> getPojoClass() {
		return PipelineRunImpl.class;
	}

	@Override
	public PipelineRun createPipelineRun(UUID userUuid, UUID pipelineUuid, int pipelineVersion) {
		PipelineRun run = new PipelineRunImpl();
		run.setKind(PipelineRunKind.PIPELINE);
		run.setPipelineUuid(pipelineUuid);
		run.setPipelineVersion(pipelineVersion);
		setCreatorEditor(run, userUuid);
		return run;
	}

	@Override
	public PipelineRun createAdhocRun(UUID userUuid, JsonObject definition) {
		PipelineRun run = new PipelineRunImpl();
		run.setKind(PipelineRunKind.ADHOC);
		// Left null on purpose - the CHECK constraint added in V2.82 rejects an ADHOC row that names
		// a pipeline, so the pairing cannot drift.
		run.setPipelineUuid(null);
		run.setMeta(new JsonObject().put(META_DEFINITION, definition));
		setCreatorEditor(run, userUuid);
		return run;
	}

	@Override
	public Page<PipelineRun> loadAdhocPageByCreator(UUID creatorUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx().selectFrom(PIPELINE_RUN)
			.where(PIPELINE_RUN.KIND.eq(PipelineRunKind.ADHOC))
			.and(PIPELINE_RUN.CREATOR_UUID.eq(creatorUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public int countActiveAdhocByCreator(UUID creatorUuid) {
		List<PipelineRunStatus> active = Arrays.stream(PipelineRunStatus.values())
			.filter(status -> !status.isTerminal())
			.toList();
		return ctx().fetchCount(PIPELINE_RUN, PIPELINE_RUN.KIND.eq(PipelineRunKind.ADHOC)
			.and(PIPELINE_RUN.CREATOR_UUID.eq(creatorUuid))
			.and(PIPELINE_RUN.STATUS.in(active)));
	}

	@Override
	public List<PipelineRun> loadByPipeline(UUID pipelineUuid) {
		return ctx().selectFrom(PIPELINE_RUN)
			.where(PIPELINE_RUN.PIPELINE_UUID.eq(pipelineUuid))
			.orderBy(PIPELINE_RUN.STARTED.desc())
			.fetchInto(getPojoClass());
	}

	@Override
	public Page<PipelineRun> loadPageByPipeline(UUID pipelineUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx().selectFrom(PIPELINE_RUN)
			.where(PIPELINE_RUN.PIPELINE_UUID.eq(pipelineUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public List<PipelineRun> loadByStatus(PipelineRunStatus status) {
		return ctx().selectFrom(PIPELINE_RUN)
			.where(PIPELINE_RUN.STATUS.eq(status))
			.orderBy(PIPELINE_RUN.STARTED.asc())
			.fetchInto(getPojoClass());
	}

	@Override
	public PipelineRun loadLatestByPipeline(UUID pipelineUuid) {
		return ctx().selectFrom(PIPELINE_RUN)
			.where(PIPELINE_RUN.PIPELINE_UUID.eq(pipelineUuid))
			.orderBy(PIPELINE_RUN.STARTED.desc())
			.limit(1)
			.fetchOptionalInto(getPojoClass())
			.orElse(null);
	}

	@Override
	public List<PipelineRunDayStats> loadDailyStats(LocalDateTime since) {
		Field<LocalDateTime> day = DSL.trunc(PIPELINE_RUN.STARTED, DatePart.DAY);
		Field<Integer> runCount = DSL.count();
		Field<Integer> successSum = DSL.sum(DSL.coalesce(PIPELINE_RUN.SUCCESS_COUNT, DSL.inline(0))).cast(Integer.class);
		Field<Integer> failureSum = DSL.sum(DSL.coalesce(PIPELINE_RUN.FAILURE_COUNT, DSL.inline(0))).cast(Integer.class);
		Field<Integer> skippedSum = DSL.sum(DSL.coalesce(PIPELINE_RUN.SKIPPED_COUNT, DSL.inline(0))).cast(Integer.class);
		return ctx().select(day, runCount, successSum, failureSum, skippedSum)
			.from(PIPELINE_RUN)
			.where(PIPELINE_RUN.STARTED.ge(since))
			// Ad-hoc runs are excluded deliberately. /pipelines/runs/stats answers "how is the
			// scheduled processing doing"; a chat agent probing twenty assets is not that, and mixing
			// the two makes the throughput chart unreadable the first time somebody uses the agent.
			// They are listed under /api/v1/node-runs instead. See spec/chat/AGENTIC_NODE_EXECUTION.md.
			.and(PIPELINE_RUN.KIND.eq(PipelineRunKind.PIPELINE))
			.groupBy(day)
			.orderBy(day.asc())
			.fetch(r -> new PipelineRunDayStats(
				r.value1().toLocalDate(),
				r.value2(),
				r.value3(),
				r.value4(),
				r.value5()));
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.STATUS) {
			return query.and(PIPELINE_RUN.STATUS.eq(parseStatus(filter.valueStr())));
		}
		if (key == LoomFilterKey.DRY_RUN) {
			return query.and(PIPELINE_RUN.DRY_RUN.eq(filter.valueBool()));
		}
		return super.applyFilter(query, filter);
	}

	/**
	 * A caller filtering on a status that does not exist has made a bad request, not found nothing.
	 * Returning an empty page instead would hide the typo behind a plausible answer.
	 */
	private static PipelineRunStatus parseStatus(String value) {
		try {
			return PipelineRunStatus.parse("pipeline_run.status", value);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, e.getMessage());
		}
	}

}