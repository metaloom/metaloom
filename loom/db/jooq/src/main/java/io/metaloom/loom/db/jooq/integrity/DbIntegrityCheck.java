package io.metaloom.loom.db.jooq.integrity;

import java.util.List;

import org.jooq.DSLContext;

import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityFinding;

/**
 * One named invariant, and the two queries that decide whether the database still holds it.
 *
 * <p>
 * The split into {@link #count(DSLContext)} and {@link #sample(DSLContext, int)} is what makes this
 * affordable to run after every CRUD operation in the test suite. The clean case - which is what
 * almost every run is - costs one aggregate and never materialises a row. Samples are only fetched
 * once the count has said there is something to look at.
 * </p>
 *
 * <p>
 * Checks are stateless. They are constructed once into the {@link DbIntegrityChecks} registry and
 * take their {@link DSLContext} as a parameter, so the same instance serves every request and every
 * test database.
 * </p>
 *
 * <p>
 * Most implementations should extend {@link AbstractConditionCheck} (a table plus a
 * {@link org.jooq.Condition}) or {@link AbstractSqlCheck} (raw SQL, for the predicates jOOQ's DSL
 * cannot express - {@code num_nonnulls}, {@code array_length}, the polymorphic anti-joins).
 * Implementing this interface directly is for checks that are neither.
 * </p>
 */
public interface DbIntegrityCheck {

	/** What this check is: code, category, severity, what it looks at, and what a finding means. */
	DbIntegrityCheckInfo info();

	/**
	 * How many rows violate the invariant.
	 *
	 * @param ctx
	 *            the context to query through
	 * @return offending row count, 0 when the invariant holds
	 */
	long count(DSLContext ctx);

	/**
	 * Name some of the offending rows. Only called when {@link #count(DSLContext)} returned non-zero,
	 * so an implementation need not handle the clean case efficiently.
	 *
	 * @param ctx
	 *            the context to query through
	 * @param limit
	 *            maximum number of rows to name
	 * @return up to {@code limit} findings
	 */
	List<DbIntegrityFinding> sample(DSLContext ctx, int limit);

	/** Convenience for tests and log lines. */
	default String code() {
		return info().code();
	}
}
