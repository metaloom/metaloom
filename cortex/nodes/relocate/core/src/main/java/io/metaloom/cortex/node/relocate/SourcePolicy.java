package io.metaloom.cortex.node.relocate;

/**
 * What happens to the original file once the bytes are somewhere else.
 *
 * <p>
 * 🔴 This is the only option in the node that can destroy data, so it is opt-in and its permissive value is spelled out rather than implied.
 * </p>
 */
public enum SourcePolicy {

	/**
	 * Leave the original where it is. The default, and the only safe answer when a destination cannot be verified.
	 *
	 * <p>
	 * With this the node performs a copy, not a move, and says so: the {@code flag} port reads {@code COPIED} rather than {@code MOVED}. Reporting a
	 * copy as a move is how a cold-tier run silently fails to reclaim anything.
	 * </p>
	 */
	KEEP,

	/**
	 * Remove the original, but only after the destination has been proved to hold the same bytes.
	 *
	 * <p>
	 * Never reached on any failure path. A same-filesystem move does not consult this at all - a rename has no second copy to remove.
	 * </p>
	 */
	DELETE_AFTER_VERIFY;

	public static SourcePolicy parse(String value) {
		if (value == null || value.isBlank()) {
			return KEEP;
		}
		for (SourcePolicy policy : values()) {
			if (policy.name().equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		throw new IllegalArgumentException("Unknown source policy {" + value + "}. Accepted values: KEEP, DELETE_AFTER_VERIFY");
	}
}
