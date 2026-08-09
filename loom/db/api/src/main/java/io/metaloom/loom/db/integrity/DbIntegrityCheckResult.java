package io.metaloom.loom.db.integrity;

import java.util.List;

/**
 * The outcome of running one check.
 *
 * <p>
 * Carries a <em>count</em> plus a capped sample rather than the offending rows themselves. A broken
 * trigger can leave six-figure orphan counts, and neither a JUnit failure message nor a JSON
 * response should try to carry them.
 * </p>
 *
 * @param check
 *            the catalogue entry, so a result is readable without a second lookup
 * @param count
 *            total offending rows. {@code 0} means the check passed
 * @param samples
 *            up to {@code DbIntegrityScope#sampleLimit()} of the offending rows; empty when
 *            {@code count} is 0, because samples are only fetched once the count says it is worth it
 * @param durationMs
 *            wall time of the count plus, when it ran, the sample query
 * @param error
 *            non-null only when the check itself threw - a dropped column after a migration, say.
 *            One broken check must not take the whole report down, so it is recorded and the sweep
 *            continues
 */
public record DbIntegrityCheckResult(
	DbIntegrityCheckInfo check,
	long count,
	List<DbIntegrityFinding> samples,
	long durationMs,
	String error) {

	public DbIntegrityCheckResult {
		samples = samples == null ? List.of() : List.copyOf(samples);
	}

	public static DbIntegrityCheckResult clean(DbIntegrityCheckInfo check, long durationMs) {
		return new DbIntegrityCheckResult(check, 0, List.of(), durationMs, null);
	}

	public static DbIntegrityCheckResult failed(DbIntegrityCheckInfo check, Throwable cause, long durationMs) {
		String message = cause.getClass().getSimpleName()
			+ (cause.getMessage() == null ? "" : ": " + cause.getMessage());
		return new DbIntegrityCheckResult(check, 0, List.of(), durationMs, message);
	}

	/** No findings and no execution error. */
	public boolean isClean() {
		return count == 0 && error == null;
	}

	public String code() {
		return check.code();
	}

	public DbIntegritySeverity severity() {
		return check.severity();
	}
}
