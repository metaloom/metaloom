package io.metaloom.loom.api.sort;

public enum SortDirection {
	ASCENDING, DESCENDING;

	public static SortDirection fromString(String value) {
		if (value != null && value.toLowerCase().trim().startsWith("desc")) {
			return DESCENDING;
		} else {
			return ASCENDING;
		}
	}
}
