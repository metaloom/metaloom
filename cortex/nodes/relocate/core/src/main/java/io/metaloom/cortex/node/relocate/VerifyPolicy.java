package io.metaloom.cortex.node.relocate;

/**
 * How thoroughly the destination is proved to hold what the source held, before the source may be removed.
 */
public enum VerifyPolicy {

	/**
	 * The destination exists and is the same length.
	 *
	 * <p>
	 * Enough for a content-addressed destination, where the key is derived from the hash: a wrong object at that key would have to collide with the
	 * hash to get there. Also the only option for an S3 destination without downloading the object back.
	 * </p>
	 */
	SIZE,

	/**
	 * The destination digests to the same SHA-512 as the source.
	 *
	 * <p>
	 * The default for filesystem destinations. It costs a full read of the copy, which is the right price for permission to delete the original.
	 * </p>
	 */
	SHA512;

	public static VerifyPolicy parse(String value) {
		if (value == null || value.isBlank()) {
			return SHA512;
		}
		for (VerifyPolicy policy : values()) {
			if (policy.name().equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		throw new IllegalArgumentException("Unknown verify policy {" + value + "}. Accepted values: SIZE, SHA512");
	}
}
