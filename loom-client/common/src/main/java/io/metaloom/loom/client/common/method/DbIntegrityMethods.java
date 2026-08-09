package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityCheckListResponse;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityReportResponse;

/**
 * The database integrity report: which invariants the database still holds, and which it does not.
 */
public interface DbIntegrityMethods {

	/**
	 * Run every registered integrity check and report the findings.
	 *
	 * @return the report request
	 */
	LoomClientRequest<DbIntegrityReportResponse> loadDbIntegrityReport();

	/**
	 * Run a filtered subset of the checks.
	 *
	 * <p>
	 * An unknown code or category is a 400 rather than an empty report - a mistyped filter must not
	 * read as a clean database.
	 * </p>
	 *
	 * @param check
	 *            a single check code, or null for all
	 * @param category
	 *            a single category, or null for all
	 * @param severity
	 *            minimum severity to include, or null for all
	 * @return the report request
	 */
	LoomClientRequest<DbIntegrityReportResponse> loadDbIntegrityReport(String check, String category,
		String severity);

	/**
	 * List the registered checks without running any of them.
	 *
	 * @return the catalogue request
	 */
	LoomClientRequest<DbIntegrityCheckListResponse> loadDbIntegrityChecks();
}
