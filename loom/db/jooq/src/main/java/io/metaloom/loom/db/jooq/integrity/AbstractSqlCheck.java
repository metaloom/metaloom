package io.metaloom.loom.db.jooq.integrity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityFinding;

/**
 * A check written as raw SQL, for the predicates the jOOQ DSL cannot express: {@code num_nonnulls},
 * {@code array_length}, the polymorphic anti-joins against {@code search_document} and
 * {@code memory_entry}, and the multi-table sweeps that would otherwise be a dozen near-identical
 * check classes.
 *
 * <p>
 * Same escape hatch, and the same reasoning, as
 * {@code io.metaloom.loom.db.jooq.search.PostgresSearchProvider}. The SQL here is entirely
 * hand-written and holds no caller input; the only value bound at runtime is the row limit.
 * </p>
 */
public abstract class AbstractSqlCheck implements DbIntegrityCheck {

	private final DbIntegrityCheckInfo info;

	protected AbstractSqlCheck(DbIntegrityCheckInfo info) {
		this.info = info;
	}

	@Override
	public DbIntegrityCheckInfo info() {
		return info;
	}

	/** A statement returning exactly one row with one numeric column: the offending row count. */
	protected abstract String countSql();

	/**
	 * A statement returning the offending rows. The first column must be the row's uuid (or NULL for
	 * a table without one); every further column is rendered into the finding's detail. Do not add a
	 * {@code LIMIT} - one is appended.
	 */
	protected abstract String sampleSql();

	@Override
	public long count(DSLContext ctx) {
		Record row = ctx.fetchOne(countSql());
		if (row == null || row.get(0) == null) {
			return 0;
		}
		return ((Number) row.get(0)).longValue();
	}

	@Override
	public List<DbIntegrityFinding> sample(DSLContext ctx, int limit) {
		List<DbIntegrityFinding> findings = new ArrayList<>();
		for (Record row : ctx.fetch(sampleSql() + " limit ?", limit)) {
			Object first = row.size() > 0 ? row.get(0) : null;
			UUID uuid = first instanceof UUID u ? u : null;
			// A non-uuid first column is data, not an identifier, so keep it in the detail.
			int detailFrom = (first == null || uuid != null) ? 1 : 0;
			findings.add(new DbIntegrityFinding(uuid, IntegrityDetails.render(row, detailFrom)));
		}
		return findings;
	}
}
