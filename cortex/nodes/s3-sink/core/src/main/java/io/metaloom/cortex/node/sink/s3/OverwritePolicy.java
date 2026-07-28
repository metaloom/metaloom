package io.metaloom.cortex.node.sink.s3;

import java.util.Locale;

/**
 * What the sink does when an object already exists at the target key.
 *
 * <p>Checked with a {@code HEAD} before each {@code PUT}. One small round trip against a
 * multi-megabyte upload is a good trade, and it is what turns a re-run over an already-published
 * corpus from N uploads per asset into N head requests.</p>
 */
public enum OverwritePolicy {

	/** Never re-upload over an existing object. */
	NEVER,

	/**
	 * Skip when an object exists at that key with the same size.
	 *
	 * <p>The default. With a content-addressed key template - which the default template is - "same
	 * key, same size" is a strong statement that the identical bytes are already there. Note it is
	 * key + size, not a content comparison: an ETag is not a content hash, because a multipart
	 * upload reports an md5-of-md5s.</p>
	 */
	IF_DIFFERENT,

	/** Always upload, replacing whatever is there. */
	ALWAYS;

	/**
	 * @param value a case-insensitive name, or null
	 * @return the policy
	 * @throws IllegalArgumentException naming the valid values
	 */
	public static OverwritePolicy parse(String value) {
		if (value == null || value.isBlank()) {
			return IF_DIFFERENT;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("unknown overwrite policy '" + value
				+ "' (allowed: NEVER, IF_DIFFERENT, ALWAYS)");
		}
	}
}
