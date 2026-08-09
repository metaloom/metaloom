package io.metaloom.cortex.fs;

/**
 * What to do when the source and the destination turn out to be on different filesystems.
 *
 * <p>
 * The distinction is worth a policy because the two cases are not the same operation. Within one filesystem a move is a rename: constant time,
 * atomic, and the extended attributes come along for free. Across a boundary it is a full byte copy followed by a delete - unbounded, interruptible,
 * and it has to carry the attributes by hand.
 * </p>
 */
public enum CrossDevicePolicy {

	/**
	 * Copy the bytes, then verify and only then remove the source if asked to. Logs a warning naming both stores and the byte count, because an
	 * operator who did not expect a copy should be able to find out from the log why a run took hours.
	 */
	COPY,

	/**
	 * Leave the file alone and report a skip. The shipped default: a worker should not silently copy 40 GB because a folder happened to be on another
	 * mount.
	 */
	SKIP,

	/**
	 * Treat a cross-device destination as a configuration error.
	 */
	FAIL;

	public static CrossDevicePolicy parse(String value) {
		if (value == null || value.isBlank()) {
			return SKIP;
		}
		for (CrossDevicePolicy policy : values()) {
			if (policy.name().equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		throw new IllegalArgumentException("Unknown cross-device policy {" + value + "}. Accepted values: COPY, SKIP, FAIL");
	}
}
