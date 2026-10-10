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
	 * Never unlink a second copy of the bytes. The default, and the only safe answer when a destination cannot be verified.
	 *
	 * <p>
	 * ⚠️ This governs the paths that <b>produce a second copy</b> - a cross-device copy and an S3 upload. On those the node copies rather than moves and
	 * says so: the {@code flag} port reads {@code COPIED} rather than {@code MOVED}. Reporting a copy as a move is how a cold-tier run silently fails to
	 * reclaim anything.
	 * </p>
	 *
	 * <p>
	 * It does <b>not</b> turn a same-filesystem relocation into a copy. There a move is a rename: there is no second copy to keep or remove, the bytes
	 * are never duplicated, and the file ends up at the destination under either policy - the {@code flag} port then reads {@code MOVED}. A {@code move}
	 * node pointed at a folder on the same mount will move the file; use {@code dryRun} to find out where it would go without it happening.
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
