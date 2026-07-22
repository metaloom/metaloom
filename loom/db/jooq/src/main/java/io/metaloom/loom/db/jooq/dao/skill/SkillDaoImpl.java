package io.metaloom.loom.db.jooq.dao.skill;

import static io.metaloom.loom.db.jooq.tables.JooqSkill.SKILL;

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
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class SkillDaoImpl extends AbstractJooqDao<Skill> implements SkillDao {

	@Inject
	public SkillDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Skills";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return SKILL;
	}

	@Override
	protected Class<? extends Skill> getPojoClass() {
		return SkillImpl.class;
	}

	@Override
	public Skill createSkill(UUID userUuid, String name, String description, String content) {
		Skill skill = new SkillImpl();
		skill.setName(name);
		skill.setDescription(description);
		skill.setContent(content);
		setCreatorEditor(skill, userUuid);
		return skill;
	}

	@Override
	public Page<Skill> findByCreator(UUID userUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.where(SKILL.CREATOR_UUID.eq(userUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public List<Skill> loadByUuids(List<UUID> uuids) {
		Objects.requireNonNull(uuids, "The uuids must be provided");
		if (uuids.isEmpty()) {
			return List.of();
		}
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(SKILL.UUID.in(uuids))
			.fetchInto(SkillImpl.class)
			.stream()
			.map(Skill.class::cast)
			.toList();
	}

	@Override
	public Page<Skill> findPublished(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.where(SKILL.PUBLISHED.isTrue());

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Skill loadByName(UUID userUuid, String name) {
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		Objects.requireNonNull(name, "The name must be provided");
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(SKILL.CREATOR_UUID.eq(userUuid).and(SKILL.NAME.eq(name)))
			.fetchOneInto(SkillImpl.class);
	}

}
