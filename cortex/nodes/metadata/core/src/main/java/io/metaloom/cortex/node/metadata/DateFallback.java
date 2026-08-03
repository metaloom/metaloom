package io.metaloom.cortex.node.metadata;

import java.util.Locale;

/**
 * What {@code dc.date} falls back to when the file itself states no creation date.
 */
public enum DateFallback {

	/** Leave {@code dc.date} null. The default: no date is a fact, a wrong date is not. */
	NONE,

	/**
	 * Use the file's modification time.
	 *
	 * <p>
	 * A filesystem timestamp records when <em>this copy</em> was written - a restore, a rsync or a
	 * download will all have rewritten it - so it answers a different question from "when was this
	 * taken". Useful when a collection has no embedded dates at all and an approximate ordering
	 * beats none; misleading everywhere else.
	 * </p>
	 */
	FILESYSTEM;

	public static DateFallback parse(String value) {
		if (value == null || value.isBlank()) {
			return NONE;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown dateFallback '" + value + "'; expected NONE or FILESYSTEM");
		}
	}
}
