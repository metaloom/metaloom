package io.metaloom.cortex.node.imagegen;

/**
 * Selects how the {@link ImageGenNode} produces its image.
 *
 * <ul>
 * <li>{@link #GENERATE} - text-to-image: create a new image purely from the
 * configured prompt (the source asset's pixels are ignored). Hits the sidecar
 * {@code /generate} endpoint.</li>
 * <li>{@link #REMIX} - image-to-image: feed the source asset's image plus the
 * prompt to the sidecar {@code /remix} endpoint.</li>
 * </ul>
 */
public enum ImageGenMode {
	GENERATE,
	REMIX
}
