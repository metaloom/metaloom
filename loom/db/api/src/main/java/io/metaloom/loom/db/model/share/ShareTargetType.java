package io.metaloom.loom.db.model.share;

/**
 * What a {@link Share} points at.
 *
 * <p>
 * The column is a {@code varchar} with a CHECK rather than a Postgres enum (V2.97), so this type is the only place the vocabulary is enforced in Java.
 * Parse with {@link #parse(String)} rather than {@link #valueOf(String)}: a row written by hand with the wrong spelling should surface as a named
 * error at the boundary, not as an {@link IllegalArgumentException} three frames deeper.
 * </p>
 */
public enum ShareTargetType {

	ASSET,

	COLLECTION;

	/**
	 * Resolve the stored column value, or {@code null} when it is absent or not a member of this vocabulary.
	 *
	 * @param value
	 *            the raw {@code share.target_type} value
	 * @return the parsed type, or null
	 */
	public static ShareTargetType parse(String value) {
		if (value == null) {
			return null;
		}
		for (ShareTargetType type : values()) {
			if (type.name().equals(value)) {
				return type;
			}
		}
		return null;
	}
}
