package io.metaloom.loom.db.integrity;

import java.util.List;

/**
 * Runs the database integrity checks and reports what they found.
 *
 * <p>
 * The interface lives in {@code loom-db-api} rather than beside its jOOQ implementation for one
 * concrete reason: {@code loom-db-jooq} already depends on {@code loom-db-api-test} (test scope), so
 * api-test can never depend on jooq. Putting the contract here is what lets
 * {@code CRUDDaoTestcases} assert integrity after every CRUD operation without a reactor cycle. It
 * is the same seam the DAO layer already uses - {@code DaoCollection} in api, {@code *DaoImpl} in
 * jooq.
 * </p>
 *
 * <p>
 * There are two audiences and they want the same data differently. The admin screen wants
 * <em>everything</em>, clean checks included, so it can say "23 checks, 0 findings". A test wants a
 * boolean and, when it is false, a message good enough to debug from. Both come off
 * {@link #check(DbIntegrityScope)}; the difference is only in what the caller does with the report.
 * </p>
 */
public interface DbIntegrityService {

	/** Run every registered check. */
	default DbIntegrityReport check() {
		return check(DbIntegrityScope.all());
	}

	/**
	 * Run the checks the scope admits.
	 *
	 * <p>
	 * A check that throws is recorded in its own {@link DbIntegrityCheckResult#error()} and the sweep
	 * continues - one check broken by a migration must not hide the other twenty-two.
	 * </p>
	 *
	 * @param scope
	 *            which checks to run and how many rows to name
	 * @return the report, with one entry per check that ran, in registry order
	 */
	DbIntegrityReport check(DbIntegrityScope scope);

	/**
	 * The catalogue: what checks exist, without running any of them. Backs
	 * {@code GET /api/v1/db-integrity/checks}.
	 *
	 * @return every registered check's descriptor, in registry order
	 */
	List<DbIntegrityCheckInfo> catalog();
}
