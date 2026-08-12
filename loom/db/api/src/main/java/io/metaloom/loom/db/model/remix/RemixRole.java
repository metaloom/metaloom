package io.metaloom.loom.db.model.remix;

/**
 * The part an asset plays inside a {@link Remix}.
 *
 * <p>
 * Stored as a varchar guarded by {@code remix_member_role_check} rather than a Postgres enum, so the
 * vocabulary can grow without an {@code ALTER TYPE} migration. Persist and read {@link #name()}.
 * </p>
 */
public enum RemixRole {

	/**
	 * The original the remix is built around.
	 *
	 * <p>
	 * At most one per remix, and the database enforces it: {@code remix_member_single_source} is a
	 * partial unique index over {@code remix_uuid} where {@code role = 'SOURCE'}. A remix may have
	 * none, which is the normal state for a group of variants whose original is not in the catalogue.
	 * </p>
	 */
	SOURCE,

	/** Anything made from the source - a cut, a re-encode, a crop, an edit. */
	DERIVED;

	/**
	 * Parse a role read back from the database, tolerating null.
	 *
	 * @param value
	 *            the raw column value
	 * @return the parsed role, or {@link #DERIVED} when the value is null or blank
	 * @throws IllegalArgumentException
	 *             if the value is neither role, which means the CHECK constraint was bypassed
	 */
	public static RemixRole parse(String value) {
		if (value == null || value.isBlank()) {
			return DERIVED;
		}
		return valueOf(value);
	}
}
