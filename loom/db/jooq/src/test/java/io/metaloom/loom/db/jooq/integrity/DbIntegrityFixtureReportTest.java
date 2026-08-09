package io.metaloom.loom.db.jooq.integrity;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.integrity.DbIntegrityReport;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.AbstractJooqTest;

/**
 * The pooled fixture database must pass its own integrity checks.
 *
 * <p>
 * This is the check on the checks. Every DAO test leases a copy of this database, and
 * {@code CRUDDaoTestcases} asserts integrity after each CRUD operation - so if the fixture itself
 * violates something, every one of those assertions fails for a reason that has nothing to do with
 * the test that tripped it. Keeping this test green is what makes the others trustworthy.
 * </p>
 *
 * <p>
 * It also prints the whole report, clean checks included, which makes it the fastest way to see what
 * the catalogue currently says about a database.
 * </p>
 */
public class DbIntegrityFixtureReportTest extends AbstractJooqTest {

	@Test
	public void testFixtureIsConsistent() {
		DbIntegrityReport report = integrityReport();

		System.out.println("Integrity report over the fixture database: " + report);
		report.results().forEach(result -> System.out.printf("  %-7s %-45s %6d  %4dms%s%n",
			result.severity(),
			result.code(),
			result.count(),
			result.durationMs(),
			result.error() == null ? "" : "  FAILED TO RUN: " + result.error()));

		// Warnings are printed but not fatal - the WARN tier exists for findings a human judges.
		if (report.has(DbIntegritySeverity.WARN)) {
			System.out.println(report.describe(DbIntegritySeverity.WARN));
		}

		assertIntegrity();
	}
}
