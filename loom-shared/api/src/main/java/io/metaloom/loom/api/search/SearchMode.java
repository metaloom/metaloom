package io.metaloom.loom.api.search;

/**
 * How a query is matched and ranked.
 */
public enum SearchMode {

	/** Full-text + trigram. The default, and the only mode the Postgres provider supports today. */
	LEXICAL,

	/** Vector similarity only. Requires {@link SearchCapability#SEMANTIC}. */
	SEMANTIC,

	/** Lexical and vector rankings fused via RRF. Requires {@link SearchCapability#HYBRID}. */
	HYBRID;

	public static SearchMode fromString(String value) {
		if (value == null || value.isBlank()) {
			return LEXICAL;
		}
		for (SearchMode mode : values()) {
			if (mode.name().equalsIgnoreCase(value)) {
				return mode;
			}
		}
		return null;
	}
}
