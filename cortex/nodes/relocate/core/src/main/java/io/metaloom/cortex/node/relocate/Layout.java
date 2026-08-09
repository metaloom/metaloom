package io.metaloom.cortex.node.relocate;

/**
 * How a destination path is built below the target root.
 */
public enum Layout {

	/**
	 * The file's own name, directly in the target root. Simple, and the most likely to collide - which is what {@code onConflict} is for.
	 */
	FLAT,

	/**
	 * Preserve the path below the configured source root, so {@code photos/2024/x.jpg} lands at {@code <target>/photos/2024/x.jpg}.
	 *
	 * <p>
	 * Falls back to {@link #FLAT} when no source root is configured: there is nothing to be relative to, and inventing one from the absolute path
	 * would recreate the whole filesystem tree under the target.
	 * </p>
	 */
	MIRROR,

	/**
	 * {@code YYYY/MM/} from the file's last-modified time, then the file name.
	 */
	DATE,

	/**
	 * The content-addressed layout Loom's own storage backends use: {@code ab/cd/ef/<sha512>}, no extension.
	 *
	 * <p>
	 * Forced for the pool, library and bucket targets. Their whole point is that the bytes end up where Loom looks for them, and Loom computes that
	 * location from the hash rather than from the file name.
	 * </p>
	 */
	CONTENT;

	public static Layout parse(String value) {
		if (value == null || value.isBlank()) {
			return MIRROR;
		}
		for (Layout layout : values()) {
			if (layout.name().equalsIgnoreCase(value.trim())) {
				return layout;
			}
		}
		throw new IllegalArgumentException("Unknown layout {" + value + "}. Accepted values: FLAT, MIRROR, DATE, CONTENT");
	}
}
