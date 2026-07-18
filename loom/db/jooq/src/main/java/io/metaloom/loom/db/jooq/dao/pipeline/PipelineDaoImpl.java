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
		setCreatorEditor(pipeline, userUuid);
		return pipeline;
	}

	@Override
	public Pipeline loadWithLatestVersion(UUID id) {
		return ctx().selectFrom(PIPELINE)
			.where(PIPELINE.UUID.eq(id))
			.fetchOneInto(getPojoClass());
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.UUID) {
			return query.and(PIPELINE.UUID.eq(UUID.fromString(filter.valueStr())));
		}
		return super.applyFilter(query, filter);
	}

}
