package io.metaloom.loom.db.integrity;

/**
 * How badly a check finding matters.
 *
 * <p>
 * The declaration order <em>is</em> the comparison order - {@link #atLeast(DbIntegritySeverity)}
 * relies on the ordinal, so never reorder these constants.
 * </p>
 */
public enum DbIntegritySeverity {

	/**
	 * Worth knowing, never worth failing a build over. Nothing reports at this level today; the
	 * constant exists so a check can be demoted without deleting it.
	 */
	INFO,

	/**
	 * Suspicious, and probably a bug, but a human has to judge. A legitimately unnamed row, a lease
	 * held by a worker that has since gone away, a child row that predates its parent by microseconds
	 * - all of these are usually fine and occasionally the first sign of something real.
	 */
	WARN,

	/**
	 * Data the application will misread or crash on, or an invariant the schema claims but does not
	 * enforce. This is the level {@code assertIntegrity()} fails on by default.
	 */
	ERROR;

	/**
	 * Whether this severity is at least as severe as the given minimum.
	 *
	 * @param min
	 *            minimum severity
	 * @return true when this severity is {@code min} or worse
	 */
	public boolean atLeast(DbIntegritySeverity min) {
		return ordinal() >= min.ordinal();
	}
}
