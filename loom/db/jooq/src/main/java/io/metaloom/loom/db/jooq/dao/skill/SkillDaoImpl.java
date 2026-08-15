package io.metaloom.loom.db.jooq.dao.skill;

import static io.metaloom.loom.db.jooq.tables.JooqSkill.SKILL;
import static io.metaloom.loom.db.jooq.tables.JooqSkillVersion.SKILL_VERSION;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.SelectJoinStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.filter.LoomFilterKey;

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

	/**
	 * Base select that joins the active {@link io.metaloom.loom.db.model.skill.SkillVersion} so that the versioned body ({@code description},
	 * {@code content}) and the active {@code version_number} are projected back onto the {@link SkillImpl} POJO. This keeps every reader of
	 * {@code Skill#getContent()} / {@code getDescription()} working even though those columns no longer live on the skill row.
	 */
	private SelectJoinStep<Record> selectWithActiveVersion() {
		return ctx()
			.select(
				SKILL.asterisk(),
				SKILL_VERSION.DESCRIPTION,
				SKILL_VERSION.CONTENT,
				SKILL_VERSION.VERSION_NUMBER.as("active_version_number"))
			.from(SKILL)
			.leftJoin(SKILL_VERSION).on(SKILL.ACTIVE_VERSION_UUID.eq(SKILL_VERSION.UUID));
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
	public Skill load(UUID uuid) {
		return selectWithActiveVersion()
			.where(SKILL.UUID.eq(uuid))
			.fetchOneInto(SkillImpl.class);
	}

	@Override
	public Page<Skill> findByCreator(UUID userUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		SelectConditionStep<?> query = selectWithActiveVersion()
			.where(SKILL.CREATOR_UUID.eq(userUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public List<Skill> loadByUuids(List<UUID> uuids) {
		Objects.requireNonNull(uuids, "The uuids must be provided");
		if (uuids.isEmpty()) {
			return List.of();
		}
		return selectWithActiveVersion()
			.where(SKILL.UUID.in(uuids))
			.fetchInto(SkillImpl.class)
			.stream()
			.map(Skill.class::cast)
			.toList();
	}

	@Override
	public Page<Skill> findPublished(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = selectWithActiveVersion()
			.where(SKILL.PUBLISHED.isTrue());

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Skill loadByName(UUID userUuid, String name) {
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		Objects.requireNonNull(name, "The name must be provided");
		return selectWithActiveVersion()
			.where(SKILL.CREATOR_UUID.eq(userUuid).and(SKILL.NAME.eq(name)))
			.fetchOneInto(SkillImpl.class);
	}

	/**
	 * Enabled and published are the two axes a skill list is actually read along: what is switched on, and what is shared with the space rather than
	 * kept private. Both are plain boolean columns, so no join is involved.
	 */
	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.ENABLED) {
			return query.and(SKILL.ENABLED.eq(filter.valueBool()));
		}
		if (key == LoomFilterKey.PUBLISHED) {
			return query.and(SKILL.PUBLISHED.eq(filter.valueBool()));
		}
		if (key == LoomFilterKey.NAME) {
			return query.and(SKILL.NAME.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}
}
