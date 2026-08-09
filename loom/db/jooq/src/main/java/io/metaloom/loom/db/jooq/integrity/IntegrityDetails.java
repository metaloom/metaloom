package io.metaloom.loom.db.jooq.integrity;

import org.jooq.Record;

/**
 * Renders the non-identifying columns of a sampled row into the one-line {@code detail} string a
 * finding carries.
 */
final class IntegrityDetails {

	/** Longest value rendered before it is cut short. A finding is a pointer, not a row dump. */
	private static final int MAX_VALUE_LENGTH = 80;

	private IntegrityDetails() {
	}

	/**
	 * @param record
	 *            the sampled row
	 * @param fromIndex
	 *            first column to render; 1 when column 0 was the uuid, 0 otherwise
	 * @return {@code col=value, col=value}, or null when there is nothing to render
	 */
	static String render(Record record, int fromIndex) {
		if (record.size() <= fromIndex) {
			return null;
		}
		StringBuilder b = new StringBuilder();
		for (int i = fromIndex; i < record.size(); i++) {
			if (b.length() > 0) {
				b.append(", ");
			}
			b.append(record.field(i).getName()).append('=').append(abbreviate(record.get(i)));
		}
		return b.toString();
	}

	private static String abbreviate(Object value) {
		if (value == null) {
			return "null";
		}
		String s = String.valueOf(value);
		if (s.length() <= MAX_VALUE_LENGTH) {
			return s;
		}
		return s.substring(0, MAX_VALUE_LENGTH) + "...";
	}
}
