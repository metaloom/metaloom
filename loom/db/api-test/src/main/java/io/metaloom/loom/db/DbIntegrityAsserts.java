package io.metaloom.loom.db;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Set;

import io.metaloom.loom.db.integrity.DbIntegrityReport;
import io.metaloom.loom.db.integrity.DbIntegrityScope;
import io.metaloom.loom.db.integrity.DbIntegrityService;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;

/**
 * Assert that the database still holds its invariants.
 *
 * <p>
 * This is the half of the integrity subsystem that exists for tests. Do something to the system -
 * store a row, delete an asset, run an endpoint - and then ask whether it left anything broken. The
 * value is in the narrowing: "something is wrong somewhere" becomes "this operation violates
 * {@code DANGLING_SEARCH_DOCUMENT}", with the offending uuids printed.
 * </p>
 *
 * <p>
 * The contract lives here, in {@code loom-db-api-test}, rather than beside the jOOQ implementation,
 * because {@code loom-db-jooq} already depends on this module for its test scope and Maven has no
 * time for reactor cycles. {@link DbIntegrityService} is in {@code loom-db-api} for the same reason.
 * </p>
 *
 * <p>
 * Implement {@link #dbIntegrity()} once, on the test base class, and every test underneath it can
 * call these.
 * </p>
 */
public interface DbIntegrityAsserts {

	/** The integrity service for the database this test is running against. */
	DbIntegrityService dbIntegrity();

	/**
	 * Check codes this test class tolerates.
	 *
	 * <p>
	 * Override to silence one specific finding, and say in a comment why the class legitimately
	 * produces it. Prefer this to switching the checks off wholesale - a class that ignores
	 * {@code MISSING_BLACKLIST_NAME} because it deliberately stores an unnamed blacklist is still
	 * being checked for everything else.
	 * </p>
	 */
	default Set<String> ignoredIntegrityChecks() {
		return Set.of();
	}

	/**
	 * Fail if any {@link DbIntegritySeverity#ERROR} check reports a finding.
	 *
	 * <p>
	 * ERROR rather than WARN is deliberate. The WARN tier holds findings a human should judge -
	 * an unnamed blacklist entry, a lease held by a departed worker - and failing a test on those
	 * would make the suite a poor place to keep them.
	 * </p>
	 */
	default void assertIntegrity() {
		assertIntegrity(DbIntegritySeverity.ERROR);
	}

	/**
	 * Fail if any check at or above {@code min} reports a finding.
	 *
	 * @param min
	 *            lowest severity to fail on
	 */
	default void assertIntegrity(DbIntegritySeverity min) {
		DbIntegrityReport report = dbIntegrity()
			.check(DbIntegrityScope.all().excluding(ignoredIntegrityChecks()));
		if (report.has(min)) {
			fail(report.describe(min));
		}
	}

	/**
	 * Fail if any of the named checks reports a finding, whatever severity it was declared at.
	 *
	 * <p>
	 * For a test that is about one invariant and wants to say so - a delete-cascade test asserting no
	 * search document was orphaned, for instance - rather than sweeping everything.
	 * </p>
	 *
	 * @param codes
	 *            check codes, from {@code DbIntegrityCodes}
	 */
	default void assertIntegrity(String... codes) {
		DbIntegrityReport report = dbIntegrity().check(DbIntegrityScope.of(codes));
		if (!report.isClean()) {
			fail(report.describe(DbIntegritySeverity.INFO));
		}
	}

	/**
	 * The raw report, for a test that wants to assert a finding <em>is</em> present.
	 *
	 * <p>
	 * That direction matters as much as the other one: a check nobody has ever seen fire is a check
	 * nobody knows works. {@code DbIntegrityServiceTest} breaks the database on purpose and uses this
	 * to prove each check notices.
	 * </p>
	 */
	default DbIntegrityReport integrityReport() {
		return dbIntegrity().check();
	}
}
