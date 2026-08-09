package io.metaloom.loom.db.jooq.integrity;

import java.util.List;
import java.util.stream.Collectors;

import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;

/**
 * One invariant asked of many tables at once - "no row anywhere was edited before it was created",
 * "no NOT NULL name column is blank", "no soft-deleted user still holds live work".
 *
 * <p>
 * Written as a single {@code UNION ALL} rather than one query per table so that a sweep costs one
 * round trip. That matters: these run after every CRUD operation in the DAO suite, and forty-seven
 * sequential round trips per assertion would not be affordable where one is.
 * </p>
 *
 * <p>
 * Each branch must produce exactly three columns - the offending row's uuid (or {@code NULL} for the
 * tables that have none), a label saying where it came from, and a detail string. Subclasses build
 * their branches with {@link #branch(String, String, String, String)}.
 * </p>
 */
public abstract class AbstractSweepCheck extends AbstractSqlCheck {

	private final String union;

	/**
	 * @param info
	 *            the catalogue entry
	 * @param branches
	 *            one SELECT per table, each returning {@code (uuid, location, detail)}
	 */
	protected AbstractSweepCheck(DbIntegrityCheckInfo info, List<String> branches) {
		super(info);
		if (branches.isEmpty()) {
			throw new IllegalArgumentException("Check " + info.code() + " sweeps nothing");
		}
		this.union = branches.stream().collect(Collectors.joining("\n  union all\n"));
	}

	/**
	 * Build one branch of the sweep.
	 *
	 * @param table
	 *            table name, unquoted - it is quoted here, which also covers the reserved ones
	 *            ({@code user}, {@code group}). The table is aliased {@code t}, so expressions and
	 *            predicates address its columns as {@code t."column"}
	 * @param idExpression
	 *            expression yielding the row's uuid, or {@code null} for a table without one
	 * @param detailExpression
	 *            expression yielding a human-readable reason, cast to text
	 * @param predicate
	 *            what makes a row offending
	 * @return the SELECT
	 */
	protected static String branch(String table, String idExpression, String detailExpression, String predicate) {
		String id = idExpression == null ? "null::uuid" : idExpression + "::uuid";
		return "  select " + id + " as uuid, '" + table + "'::text as location, "
			+ "(" + detailExpression + ")::text as detail"
			+ " from \"" + table + "\" t where " + predicate;
	}

	@Override
	protected String countSql() {
		return "select count(*) from (\n" + union + "\n) sweep";
	}

	@Override
	protected String sampleSql() {
		return "select uuid, location, detail from (\n" + union + "\n) sweep order by location";
	}
}
