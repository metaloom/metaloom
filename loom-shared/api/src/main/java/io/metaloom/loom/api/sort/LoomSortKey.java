package io.metaloom.loom.api.sort;

/**
 * The columns a list route may be ordered by, as named in {@code ?sort=}.
 *
 * <p>
 * A key is only a <em>request</em> to sort by that column. Not every type has every column, and the DAO answers 400 rather than 500 for a key its
 * table does not carry — see {@code AbstractJooqDao#getSortField(SortKey)}, which also lets a type map a key onto a differently named column (an
 * asset's display name lives in {@code filename}).
 * </p>
 */
public enum LoomSortKey implements SortKey {

	USERNAME("username"),

	FIRSTNAME("firstname"),

	LASTNAME("lastname"),

	NAME("name"),

	EMAIL("email"),

	COLLECTION("collection"),

	/**
	 * Creation timestamp. The stable default for a catalogue listing: a row's creation instant never changes, so paging over it cannot shuffle rows
	 * between pages the way {@link #NAME} does when someone renames an element mid-scroll.
	 */
	CREATED("created"),

	/** Last-modified timestamp. Sort descending for a "recently touched" feed. */
	EDITED("edited"),

	SHA512("sha512"),

	MD5("md5"),

	UUID("uuid");

	private String key;

	LoomSortKey(String key) {
		this.key = key;
	}

	@Override
	public String getKey() {
		return key;
	}

	public static SortKey fromString(String value) {
		for (SortKey key : values()) {
			if (value != null && value.equalsIgnoreCase(key.getKey())) {
				return key;
			}
		}
		return null;
	}

}
