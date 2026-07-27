package io.metaloom.loom.api.search;

/**
 * Result ordering.
 *
 * <p>
 * Note that {@link #NAME} and {@link #SIZE} order the <i>search</i> result set, which is materialized per page - they are unrelated to the
 * {@code ?sort=} parameter of the CRUD list routes, whose keyset seek cannot express these orderings.
 * </p>
 */
public enum SearchSortMode {

	RELEVANCE,

	NEWEST,

	OLDEST,

	NAME,

	SIZE;

	public static SearchSortMode fromString(String value) {
		if (value == null || value.isBlank()) {
			return RELEVANCE;
		}
		for (SearchSortMode mode : values()) {
			if (mode.name().equalsIgnoreCase(value)) {
				return mode;
			}
		}
		return null;
	}
}
