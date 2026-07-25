package io.metaloom.loom.db.jooq.dao.memory;

import static io.metaloom.loom.db.jooq.tables.JooqMemoryDenyRule.MEMORY_DENY_RULE;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;

@Singleton
public class MemoryDenyRuleDaoImpl extends AbstractJooqDao<MemoryDenyRule> implements MemoryDenyRuleDao {

	@Inject
	public MemoryDenyRuleDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Memory deny rules";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return MEMORY_DENY_RULE;
	}

	@Override
	protected Class<? extends MemoryDenyRule> getPojoClass() {
		return MemoryDenyRuleImpl.class;
	}

	@Override
	public MemoryDenyRule createMemoryDenyRule(UUID userUuid, String name, String pattern, String message) {
		Objects.requireNonNull(name, "The name must be provided");
		Objects.requireNonNull(pattern, "The pattern must be provided");
		Objects.requireNonNull(message, "The message must be provided");
		MemoryDenyRule rule = new MemoryDenyRuleImpl();
		rule.setName(name);
		rule.setPattern(pattern);
		rule.setMessage(message);
		setCreatorEditor(rule, userUuid);
		return rule;
	}

	@Override
	public MemoryDenyRule loadByName(String name) {
		Objects.requireNonNull(name, "The name must be provided");
		return ctx().selectFrom(MEMORY_DENY_RULE)
			.where(MEMORY_DENY_RULE.NAME.eq(name))
			.fetchOneInto(MemoryDenyRuleImpl.class);
	}

	@Override
	public List<MemoryDenyRule> loadEnabled() {
		return ctx().selectFrom(MEMORY_DENY_RULE)
			.where(MEMORY_DENY_RULE.ENABLED.isTrue())
			.orderBy(MEMORY_DENY_RULE.NAME.asc())
			.fetchInto(MemoryDenyRuleImpl.class)
			.stream()
			.map(MemoryDenyRule.class::cast)
			.toList();
	}

}
