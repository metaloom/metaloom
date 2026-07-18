package io.metaloom.loom.db.jooq.dao.pipeline;

import static io.metaloom.loom.db.jooq.tables.JooqPipelineRunItem.PIPELINE_RUN_ITEM;

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
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqPipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class PipelineRunItemDaoImpl extends AbstractJooqDao<PipelineRunItem> implements PipelineRunItemDao {

	/** States an item can no longer move out of. */
	private static final List<String> TERMINAL_STATES = List.of("SUCCESS", "FAILED", "SKIPPED");

	@Inject
	public PipelineRunItemDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Pipeline Run Items";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqPipelineRunItem.PIPELINE_RUN_ITEM;
	}

	@Override
	protected Class<? extends PipelineRunItem> getPojoClass() {
		return PipelineRunItemImpl.class;
	}

	@Override
	public PipelineRunItem createRunItem(UUID userUuid, UUID runUuid, long itemSeq, String mediaPath) {
		PipelineRunItem item = new PipelineRunItemImpl();
		item.setRunUuid(runUuid);
		item.setItemSeq(itemSeq);
		item.setMediaPath(mediaPath);
		setCreatorEditor(item, userUuid);
		return item;
	}

	@Override
	public List<PipelineRunItem> loadByRun(UUID runUuid) {
		return ctx().selectFrom(PIPELINE_RUN_ITEM)
			.where(PIPELINE_RUN_ITEM.RUN_UUID.eq(runUuid))
			.orderBy(PIPELINE_RUN_ITEM.ITEM_SEQ.asc())
			.fetchInto(getPojoClass());
	}

	@Override
	public List<PipelineRunItem> loadUnfinishedByRun(UUID runUuid) {
		return ctx().selectFrom(PIPELINE_RUN_ITEM)
			.where(PIPELINE_RUN_ITEM.RUN_UUID.eq(runUuid))
			.and(PIPELINE_RUN_ITEM.STATE.notIn(TERMINAL_STATES))
			.orderBy(PIPELINE_RUN_ITEM.ITEM_SEQ.asc())
			.fetchInto(getPojoClass());
	}

	@Override
	public long countByRunAndState(UUID runUuid, String state) {
		return ctx().fetchCount(PIPELINE_RUN_ITEM,
			PIPELINE_RUN_ITEM.RUN_UUID.eq(runUuid).and(PIPELINE_RUN_ITEM.STATE.eq(state)));
	}

	@Override
	public Page<PipelineRunItem> loadPageByRun(UUID runUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx().selectFrom(PIPELINE_RUN_ITEM)
			.where(PIPELINE_RUN_ITEM.RUN_UUID.eq(runUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.STATUS) {
			return query.and(PIPELINE_RUN_ITEM.STATE.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
