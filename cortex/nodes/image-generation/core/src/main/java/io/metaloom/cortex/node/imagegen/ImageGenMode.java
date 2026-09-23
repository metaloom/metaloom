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
 * <li>{@link #EDIT} - the source image plus any images wired into
 * {@code references}, and optionally a region mask, to the sidecar {@code /edit}
 * endpoint. This is the multi-image mode: composing several pictures into one, and
 * changing only a named region of one.</li>
 * <li>{@link #MASK} - produce a binary mask of the region named by
 * {@code maskPrompt}, white where the region is. Hits {@code /mask}. The output is
 * an image like any other, so it is wired into a second instance's {@code mask} port
 * to make the two-step "change only the hair" flow.</li>
 * </ul>
 *
 * <p>
 * {@code EDIT} and {@code MASK} need a sidecar that serves those two endpoints. Of the
 * three backends only {@code qwen-image-sidecar} (port 9230) does; pointing them at
 * ideogram (9200) or mage-flow (9210) fails the item with the sidecar's own 404. The
 * node cannot check this up front because it never calls {@code /health} - see
 * NODE_IMAGEGEN.md.
 * </p>
 */
public enum ImageGenMode {
	GENERATE,
	REMIX,
	EDIT,
	MASK
}
