package io.metaloom.cortex.fs;

/**
 * What to do when the destination of a move is already occupied.
 *
 * <p>
 * ⚠️ There is deliberately no {@code OVERWRITE}. A collision in a trash or archive folder is very often a genuinely different asset that happens to
 * share a file name, and overwriting it destroys the only copy - the one failure mode a mover must not have.
 * </p>
 */
public enum ConflictPolicy {

	/**
	 * Land next to the occupant under a numbered name: {@code clip_1.mp4}, {@code clip_2.mp4}, and so on.
	 */
	SUFFIX,

	/**
	 * Leave the source where it is and report that nothing was done.
	 */
	SKIP,

	/**
	 * Treat the collision as an error.
	 */
	FAIL;

	/**
	 * Parse a policy name, listing the accepted values when it does not match.
	 *
	 * @param value
	 * @return
	 */
	public static ConflictPolicy parse(String value) {
		if (value == null || value.isBlank()) {
			return SUFFIX;
		}
		for (ConflictPolicy policy : values()) {
			if (policy.name().equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		throw new IllegalArgumentException("Unknown conflict policy {" + value + "}. Accepted values: SUFFIX, SKIP, FAIL");
	}
}
