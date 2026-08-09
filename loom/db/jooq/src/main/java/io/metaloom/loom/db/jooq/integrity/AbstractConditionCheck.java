package io.metaloom.loom.db.jooq.integrity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.Table;

import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityFinding;

/**
 * A check that is one table and one {@link Condition} over the generated jOOQ tables.
 *
 * <p>
 * This is the base to reach for first. Because it addresses columns through the generated
 * {@code Jooq*} constants, a renamed or dropped column breaks the build rather than quietly turning
 * the check into a no-op - which matters more here than anywhere else in the tree, since a check
 * that silently stops checking is worse than no check at all.
 * </p>
 *
 * <p>
 * Subclasses that need a predicate the DSL cannot express - {@code num_nonnulls},
 * {@code array_length}, a polymorphic anti-join - use {@link AbstractSqlCheck} instead.
 * </p>
 */
public abstract class AbstractConditionCheck implements DbIntegrityCheck {

	private final DbIntegrityCheckInfo info;

	protected AbstractConditionCheck(DbIntegrityCheckInfo info) {
		this.info = info;
	}

	@Override
	public DbIntegrityCheckInfo info() {
		return info;
	}

	/** The table to scan. */
	protected abstract Table<?> table();

	/** What makes a row offending. */
	protected abstract Condition condition();

	/**
	 * The field a finding is named by. Defaults to the table's {@code uuid} column, and returns null
	 * for the tables that have none - {@code loom}, {@code task_assignee}, {@code search_document},
	 * every join table. A null id is fine: the finding is then described by {@link #detailFields()}
	 * alone.
	 */
	protected Field<UUID> idField() {
		return table().field("uuid", UUID.class);
	}

	/** Extra columns to render into the finding, so a reader can see why the row was flagged. */
	protected Field<?>[] detailFields() {
		return new Field<?>[0];
	}

	@Override
	public long count(DSLContext ctx) {
		return ctx.fetchCount(table(), condition());
	}

	@Override
	public List<DbIntegrityFinding> sample(DSLContext ctx, int limit) {
		Field<UUID> id = idField();
		Field<?>[] details = detailFields();

		List<SelectFieldOrAsterisk> selected = new ArrayList<>();
		if (id != null) {
			selected.add(id);
		}
		for (Field<?> f : details) {
			selected.add(f);
		}
		if (selected.isEmpty()) {
			// Nothing addressable to report. The count still carries the signal.
			return List.of();
		}

		List<DbIntegrityFinding> findings = new ArrayList<>();
		for (Record record : ctx.select(selected).from(table()).where(condition()).limit(limit).fetch()) {
			UUID uuid = id == null ? null : record.get(id);
			findings.add(new DbIntegrityFinding(uuid, IntegrityDetails.render(record, id == null ? 0 : 1)));
		}
		return findings;
	}
}
