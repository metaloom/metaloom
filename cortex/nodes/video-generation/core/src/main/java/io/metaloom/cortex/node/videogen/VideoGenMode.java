package io.metaloom.cortex.node.videogen;

/**
 * Selects how the {@link VideoGenNode} produces its clip.
 *
 * <ul>
 * <li>{@link #GENERATE} - text-to-video: synthesise a new clip purely from the
 * configured prompt (the source asset's pixels are ignored). Hits the sidecar
 * {@code /generate} endpoint.</li>
 * <li>{@link #ANIMATE} - image-to-video: feed the source asset's still image plus
 * the prompt to the sidecar {@code /animate} endpoint, using the image as the
 * opening frame.</li>
 * </ul>
 */
public enum VideoGenMode {
	GENERATE,
	ANIMATE
}
