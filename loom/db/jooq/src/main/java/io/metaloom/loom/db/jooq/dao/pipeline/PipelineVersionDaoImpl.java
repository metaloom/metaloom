package io.metaloom.loom.db.jooq.dao.pipeline;

import static io.metaloom.loom.db.jooq.tables.JooqPipelineVersion.PIPELINE_VERSION;

import java.util.Collection;
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
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqPipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.vertx.core.json.JsonObject;

@Singleton
public class PipelineVersionDaoImpl extends AbstractJooqDao<PipelineVersion> implements PipelineVersionDao {

	@Inject
	public PipelineVersionDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "PipelineVersions";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqPipelineVersion.PIPELINE_VERSION;
	}

	@Override
	protected Class<? extends PipelineVersion> getPojoClass() {
		return PipelineVersionImpl.class;
	}

	@Override
	public PipelineVersion createVersion(UUID userUuid, UUID pipelineUuid, int versionNumber, String name, String description, JsonObject definition, boolean enabled, int priority, boolean dryRun, JsonObject meta) {
		PipelineVersion version = new PipelineVersionImpl();
		version.setPipelineUuid(pipelineUuid);
		version.setVersionNumber(versionNumber);
		version.setName(name);
		version.setDescription(description);
		version.setDefinition(definition);
		version.setEnabled(enabled);
		version.setPriority(priority);
		version.setDryRun(dryRun);
		version.setMeta(meta);
		setCreatorEditor(version, userUuid);
		return version;
	}

	@Override
	public List<PipelineVersion> loadByPipeline(UUID pipelineUuid) {
		return ctx().selectFrom(PIPELINE_VERSION)
			.where(PIPELINE_VERSION.PIPELINE_UUID.eq(pipelineUuid))
			.orderBy(PIPELINE_VERSION.VERSION_NUMBER.asc())
			.fetchInto(getPojoClass());
	}

	@Override
	public List<PipelineVersion> loadByUuids(Collection<UUID> uuids) {
		if (uuids == null || uuids.isEmpty()) {
			return List.of();
		}
		return ctx().selectFrom(PIPELINE_VERSION)
			.where(PIPELINE_VERSION.UUID.in(uuids))
			.fetchInto(getPojoClass());
	}

	@Override
	public io.metaloom.loom.db.page.Page<PipelineVersion> loadPageByPipeline(UUID pipelineUuid, UUID fromId, int pageSize, List<Filter> filters, io.metaloom.loom.api.sort.SortKey sortBy, io.metaloom.loom.api.sort.SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select(PIPELINE_VERSION)
			.from(PIPELINE_VERSION)
			.where(PIPELINE_VERSION.PIPELINE_UUID.eq(pipelineUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public PipelineVersion loadLatestByPipeline(UUID pipelineUuid) {
		return ctx().selectFrom(PIPELINE_VERSION)
			.where(PIPELINE_VERSION.PIPELINE_UUID.eq(pipelineUuid))
			.orderBy(PIPELINE_VERSION.VERSION_NUMBER.desc())
			.limit(1)
			.fetchOneInto(getPojoClass());
	}

	@Override
	public PipelineVersion loadByPipelineAndVersion(UUID pipelineUuid, int versionNumber) {
		return ctx().selectFrom(PIPELINE_VERSION)
			.where(PIPELINE_VERSION.PIPELINE_UUID.eq(pipelineUuid)
				.and(PIPELINE_VERSION.VERSION_NUMBER.eq(versionNumber)))
			.fetchOneInto(getPojoClass());
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.UUID) {
			return query.and(getTable().field("uuid", UUID.class).eq(UUID.fromString(filter.valueStr())));
		}
		return super.applyFilter(query, filter);
	}

}