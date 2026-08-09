package io.metaloom.loom.db.model.review;

/**
 * The verdict a human can record against something a producer proposed.
 *
 * <p>
 * Backed by the PostgreSQL {@code review_status} enum, shared by {@code detection} and {@code cluster} (and reserved for the safety-triage verdict).
 * The type was introduced as {@code cluster_status} in {@code V2.79} and renamed in {@code V2.81} once a second table needed the same three values.
 * </p>
 *
 * <p>
 * These are {@link String} constants rather than a Java enum on purpose. The DAO layer converts to and from the generated {@code JooqReviewStatus} at
 * the database boundary, and keeping the domain type a String means {@code loom-db-api} does not depend on generated code - the same arrangement
 * {@code dedup_group} uses.
 * </p>
 */
public final class ReviewStatus {

	/** Nobody has decided yet. The default a producer writes with. */
	public static final String PENDING = "PENDING";

	/** A human agreed with the proposal. */
	public static final String CONFIRMED = "CONFIRMED";

	/** A human disagreed: the proposal is a false positive. */
	public static final String REJECTED = "REJECTED";

	private ReviewStatus() {
	}

	/**
	 * Return true when the given value is one of the three known verdicts. Null is not valid - use the caller's own default first.
	 *
	 * @param status the value to check
	 * @return whether the value names a review status
	 */
	public static boolean isValid(String status) {
		return PENDING.equals(status) || CONFIRMED.equals(status) || REJECTED.equals(status);
	}
}
