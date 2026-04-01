package io.metaloom.loom.api.sort;

public enum LoomSortKey implements SortKey {

	USERNAME("username"),

	FIRSTNAME("firstname"),

	LASTNAME("firstname"),

	NAME("name"),

	EMAIL("email"),

	COLLECTION("collection"),

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
