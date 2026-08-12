package io.metaloom.loom.api.search;

/**
 * Kind of thing a {@link SearchHit} points at.
 *
 * <p>
 * The wire form is the lowercase {@link #id()}, which is also the value stored in {@code search_document.entity_type}. Keep the two in sync - the
 * Postgres provider filters on the raw string.
 * </p>
 */
public enum SearchEntityType {

	ASSET("asset"),

	/** A transcript span. Carries {@code timeFromMs} so a hit can deep-link into the player. */
	TRANSCRIPT("transcript"),

	TAG("tag"),

	ANNOTATION("annotation"),

	PERSON("person"),

	COLLECTION("collection"),

	/** A named group of assets that are versions of one another. Keywords carry the member filenames. */
	REMIX("remix"),

	LIBRARY("library"),

	DETECTION("detection"),

	/** A scene / chapter / silence range of an asset. */
	SEGMENT("segment"),

	CLUSTER("cluster");

	private final String id;

	SearchEntityType(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	/**
	 * Parse a wire value.
	 *
	 * @param value
	 *            lowercase id, case insensitive
	 * @return the matching type, or null when the value is null, blank or unknown
	 */
	public static SearchEntityType fromString(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (SearchEntityType type : values()) {
			if (type.id.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}
		return null;
	}
}
