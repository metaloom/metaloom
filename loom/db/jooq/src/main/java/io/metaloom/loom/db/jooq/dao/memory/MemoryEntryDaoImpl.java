package io.metaloom.loom.db.jooq.dao.memory;

import static io.metaloom.loom.db.jooq.tables.JooqMemoryEntry.MEMORY_ENTRY;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.enums.JooqMemoryScope;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.db.model.memory.MemoryEntryDao;

@Singleton
public class MemoryEntryDaoImpl extends AbstractJooqDao<MemoryEntry> implements MemoryEntryDao {

	@Inject
	public MemoryEntryDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Memory entries";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return MEMORY_ENTRY;
	}

	@Override
	protected Class<? extends MemoryEntry> getPojoClass() {
		return MemoryEntryImpl.class;
	}

	@Override
	public MemoryEntry createMemoryEntry(UUID userUuid, MemoryScope scope, UUID scopeUuid, String memoryId) {
		Objects.requireNonNull(scope, "The scope must be provided");
		Objects.requireNonNull(scopeUuid, "The scope uuid must be provided");
		Objects.requireNonNull(memoryId, "The memory id must be provided");
		MemoryEntry entry = new MemoryEntryImpl();
		entry.setScope(scope);
		entry.setScopeUuid(scopeUuid);
		entry.setMemoryId(memoryId);
		setCreatorEditor(entry, userUuid);
		return entry;
	}

	@Override
	public MemoryEntry loadByPath(MemoryScope scope, UUID scopeUuid, String memoryId) {
		Objects.requireNonNull(scope, "The scope must be provided");
		Objects.requireNonNull(scopeUuid, "The scope uuid must be provided");
		Objects.requireNonNull(memoryId, "The memory id must be provided");
		return ctx().selectFrom(MEMORY_ENTRY)
			.where(scopeCondition(scope, scopeUuid).and(MEMORY_ENTRY.MEMORY_ID.eq(memoryId)))
			.fetchOneInto(MemoryEntryImpl.class);
	}

	@Override
	public List<MemoryEntry> listByScope(MemoryScope scope, UUID scopeUuid, String prefix, int limit) {
		Objects.requireNonNull(scope, "The scope must be provided");
		Objects.requireNonNull(scopeUuid, "The scope uuid must be provided");
		Condition condition = scopeCondition(scope, scopeUuid);
		if (prefix != null && !prefix.isBlank()) {
			condition = condition.and(MEMORY_ENTRY.MEMORY_ID.startsWith(prefix));
		}
		return ctx().selectFrom(MEMORY_ENTRY)
			.where(condition)
			.orderBy(MEMORY_ENTRY.EDITED.desc())
			.limit(limit)
			.fetchInto(MemoryEntryImpl.class)
			.stream()
			.map(MemoryEntry.class::cast)
			.toList();
	}

	@Override
	public List<MemoryEntry> listIndex(List<MemoryScopeKey> scopes, int limit) {
		if (scopes == null || scopes.isEmpty()) {
			return List.of();
		}
		Condition condition = DSL.falseCondition();
		for (MemoryScopeKey key : scopes) {
			condition = condition.or(scopeCondition(key.scope(), key.scopeUuid()));
		}
		// The body is deliberately not projected — this runs on every agent turn.
		return ctx().select(
			MEMORY_ENTRY.UUID,
			MEMORY_ENTRY.SCOPE,
			MEMORY_ENTRY.SCOPE_UUID,
			MEMORY_ENTRY.MEMORY_ID,
			MEMORY_ENTRY.TITLE,
			MEMORY_ENTRY.SIZE,
			MEMORY_ENTRY.SHA256,
			MEMORY_ENTRY.VERSION,
			MEMORY_ENTRY.SESSION_NAME,
			MEMORY_ENTRY.CHAT_UUID,
			MEMORY_ENTRY.CREATED,
			MEMORY_ENTRY.CREATOR_UUID,
			MEMORY_ENTRY.EDITED,
			MEMORY_ENTRY.EDITOR_UUID)
			.from(MEMORY_ENTRY)
			.where(condition)
			.orderBy(MEMORY_ENTRY.EDITED.desc())
			.limit(limit)
			.fetchInto(MemoryEntryImpl.class)
			.stream()
			.map(MemoryEntry.class::cast)
			.toList();
	}

	@Override
	public MemoryScopeStats stats(MemoryScope scope, UUID scopeUuid) {
		Objects.requireNonNull(scope, "The scope must be provided");
		Objects.requireNonNull(scopeUuid, "The scope uuid must be provided");
		Record2<Integer, BigDecimal> record = ctx()
			.select(DSL.count(), DSL.sum(MEMORY_ENTRY.SIZE))
			.from(MEMORY_ENTRY)
			.where(scopeCondition(scope, scopeUuid))
			.fetchOne();
		if (record == null) {
			return MemoryScopeStats.EMPTY;
		}
		Integer count = record.value1();
		BigDecimal bytes = record.value2();
		return new MemoryScopeStats(count == null ? 0 : count, bytes == null ? 0L : bytes.longValue());
	}

	@Override
	public boolean deleteByPath(MemoryScope scope, UUID scopeUuid, String memoryId) {
		Objects.requireNonNull(scope, "The scope must be provided");
		Objects.requireNonNull(scopeUuid, "The scope uuid must be provided");
		Objects.requireNonNull(memoryId, "The memory id must be provided");
		return ctx().deleteFrom(MEMORY_ENTRY)
			.where(scopeCondition(scope, scopeUuid).and(MEMORY_ENTRY.MEMORY_ID.eq(memoryId)))
			.execute() > 0;
	}

	/**
	 * Every memory query is keyed by the scope pair — there is no query path which can return rows outside a resolved scope.
	 */
	private Condition scopeCondition(MemoryScope scope, UUID scopeUuid) {
		return MEMORY_ENTRY.SCOPE.eq(JooqMemoryScope.valueOf(scope.name()))
			.and(MEMORY_ENTRY.SCOPE_UUID.eq(scopeUuid));
	}

}
