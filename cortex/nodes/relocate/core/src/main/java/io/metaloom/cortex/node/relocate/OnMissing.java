package io.metaloom.cortex.node.relocate;

/**
 * What to do when the collection or library named on the node does not exist.
 */
public enum OnMissing {

	/**
	 * Treat it as a configuration error. The default: a name that does not resolve is almost always a typo, and quietly doing nothing for a whole run
	 * is how a curation pipeline appears to work while producing an empty set.
	 */
	FAIL,

	/**
	 * Create it. Requires {@code CREATE_COLLECTION} (or {@code CREATE_LIBRARY}) on the worker's token.
	 */
	CREATE,

	/**
	 * Leave the item alone and report a skip. For a pipeline that is allowed to run before its target exists.
	 */
	SKIP;

	public static OnMissing parse(String value) {
		if (value == null || value.isBlank()) {
			return FAIL;
		}
		for (OnMissing policy : values()) {
			if (policy.name().equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		throw new IllegalArgumentException("Unknown onMissing policy {" + value + "}. Accepted values: FAIL, CREATE, SKIP");
	}
}
