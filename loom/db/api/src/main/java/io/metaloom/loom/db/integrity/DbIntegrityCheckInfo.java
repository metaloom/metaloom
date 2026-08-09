package io.metaloom.loom.db.integrity;

/**
 * What a check is, independent of whether it has been run. This is the catalogue entry: it is what
 * {@code GET /api/v1/db-integrity/checks} returns, and it is embedded in every
 * {@link DbIntegrityCheckResult} so a report is self-describing.
 *
 * @param code
 *            stable identifier, {@code SCREAMING_SNAKE_CASE}. The only part of a finding a client
 *            should branch on; the description beside it is for humans and may be reworded
 * @param category
 *            what kind of defect this looks for
 * @param severity
 *            how badly a finding matters
 * @param table
 *            the table being checked. For a check that spans several (the soft-deleted-user sweep,
 *            the blank-name sweep) this names the theme rather than one table
 * @param column
 *            the column being checked, or {@code null} when the check is about whole rows
 * @param description
 *            one sentence saying what a finding means and why it is bad
 */
public record DbIntegrityCheckInfo(
	String code,
	DbIntegrityCategory category,
	DbIntegritySeverity severity,
	String table,
	String column,
	String description) {

	public DbIntegrityCheckInfo {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("A check needs a code");
		}
		if (category == null || severity == null) {
			throw new IllegalArgumentException("Check " + code + " needs a category and a severity");
		}
	}

	/** {@code table.column}, or just the table when the check is not column-scoped. */
	public String location() {
		return column == null ? table : table + "." + column;
	}
}
