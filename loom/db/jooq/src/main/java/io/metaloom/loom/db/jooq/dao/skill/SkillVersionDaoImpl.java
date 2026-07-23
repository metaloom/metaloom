package io.metaloom.loom.db.jooq.dao.skill;

import static io.metaloom.loom.db.jooq.tables.JooqSkillVersion.SKILL_VERSION;

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
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.metaloom.loom.db.model.skill.SkillVersionDao;
import io.metaloom.loom.db.page.Page;
import io.vertx.core.json.JsonObject;

@Singleton
public class SkillVersionDaoImpl extends AbstractJooqDao<SkillVersion> implements SkillVersionDao {

	@Inject
	public SkillVersionDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "SkillVersions";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return SKILL_VERSION;
	}

	@Override
	protected Class<? extends SkillVersion> getPojoClass() {
		return SkillVersionImpl.class;
	}

	@Override
	public SkillVersion createVersion(UUID userUuid, UUID skillUuid, int versionNumber, String description, String content, JsonObject meta) {
		SkillVersion version = new SkillVersionImpl();
		version.setSkillUuid(skillUuid);
		version.setVersionNumber(versionNumber);
		version.setDescription(description);
		version.setContent(content);
		version.setMeta(meta);
		setCreatorEditor(version, userUuid);
		return version;
	}

	@Override
	public List<SkillVersion> loadBySkill(UUID skillUuid) {
		return ctx().selectFrom(SKILL_VERSION)
			.where(SKILL_VERSION.SKILL_UUID.eq(skillUuid))
			.orderBy(SKILL_VERSION.VERSION_NUMBER.asc())
			.fetchInto(getPojoClass());
	}

	@Override
	public List<SkillVersion> loadByUuids(Collection<UUID> uuids) {
		if (uuids == null || uuids.isEmpty()) {
			return List.of();
		}
		return ctx().selectFrom(SKILL_VERSION)
			.where(SKILL_VERSION.UUID.in(uuids))
			.fetchInto(getPojoClass());
	}

	@Override
	public Page<SkillVersion> loadPageBySkill(UUID skillUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select(SKILL_VERSION)
			.from(SKILL_VERSION)
			.where(SKILL_VERSION.SKILL_UUID.eq(skillUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public SkillVersion loadLatestBySkill(UUID skillUuid) {
		return ctx().selectFrom(SKILL_VERSION)
			.where(SKILL_VERSION.SKILL_UUID.eq(skillUuid))
			.orderBy(SKILL_VERSION.VERSION_NUMBER.desc())
			.limit(1)
			.fetchOneInto(getPojoClass());
	}

	@Override
	public SkillVersion loadBySkillAndVersion(UUID skillUuid, int versionNumber) {
		return ctx().selectFrom(SKILL_VERSION)
			.where(SKILL_VERSION.SKILL_UUID.eq(skillUuid)
				.and(SKILL_VERSION.VERSION_NUMBER.eq(versionNumber)))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public int deleteVersionsAfter(UUID skillUuid, int versionNumber) {
		return ctx().deleteFrom(SKILL_VERSION)
			.where(SKILL_VERSION.SKILL_UUID.eq(skillUuid)
				.and(SKILL_VERSION.VERSION_NUMBER.gt(versionNumber)))
			.execute();
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
