package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;
import java.util.Map;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSqlCheck;

/**
 * A memory entry scoped to a user, group or space that is not there.
 *
 * <p>
 * {@code memory_entry.scope_uuid} has no foreign key <em>by design</em> - the table it points into
 * depends on {@code scope}, and no single constraint can express that. WARN rather than ERROR: the
 * agent degrades to a narrower memory rather than breaking, and a scope whose subject has been
 * deleted is a cleanup job, not a crash.
 * </p>
 */
public final class DanglingMemoryEntryScopeCheck extends AbstractSqlCheck {

	/** Which table each {@link MemoryScope} points into. SPACE is the {@code project} table. */
	private static final Map<MemoryScope, String> TABLES = Map.of(
		MemoryScope.USER, "user",
		MemoryScope.GROUP, "group",
		MemoryScope.SPACE, "project");

	public DanglingMemoryEntryScopeCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.DANGLING_MEMORY_ENTRY_SCOPE,
			"Memory entry scope target",
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.WARN,
			"memory_entry", "scope_uuid",
			"A memory entry is scoped to a user, group or space that no longer exists. The column"
				+ " has no foreign key by design - the target table depends on the scope column."));
	}

	static Map<MemoryScope, String> tables() {
		return TABLES;
	}

	private String predicate() {
		List<String> branches = TABLES.entrySet().stream()
			.map(e -> "(m.\"scope\" = '" + e.getKey().name() + "'"
				+ " and not exists (select 1 from \"" + e.getValue() + "\" t"
				+ " where t.\"uuid\" = m.\"scope_uuid\"))")
			.toList();
		return String.join("\n     or ", branches);
	}

	@Override
	protected String countSql() {
		return "select count(*) from \"memory_entry\" m where " + predicate();
	}

	@Override
	protected String sampleSql() {
		return "select m.\"uuid\", m.\"scope\", m.\"scope_uuid\" from \"memory_entry\" m where " + predicate();
	}
}
