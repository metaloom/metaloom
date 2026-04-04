package io.metaloom.loom.db.jooq.dao.pipeline;

import static io.metaloom.loom.db.jooq.tables.JooqPipeline.PIPELINE;

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
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqPipeline;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;

@Singleton
public class PipelineDaoImpl extends AbstractJooqDao<Pipeline> implements PipelineDao {

	@Inject
	public PipelineDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Pipelines";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqPipeline.PIPELINE;
	}

	@Override
	protected Class<? extends Pipeline> getPojoClass() {
		return PipelineImpl.class;
	}

	@Override
	public Pipeline createPipeline(UUID userUuid, String name) {
		Pipeline pipeline = new PipelineImpl();
		pipeline.setName(name);
		setCreatorEditor(pipeline, userUuid);
		return pipeline;
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(PIPELINE.NAME.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
