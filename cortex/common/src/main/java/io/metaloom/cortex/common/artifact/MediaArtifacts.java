package io.metaloom.cortex.common.artifact;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.artifact.Artifact;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.artifact.ArtifactException;
import io.metaloom.cortex.api.node.artifact.ArtifactKey;
import io.metaloom.cortex.api.node.context.NodeContext;

/**
 * Artifacts that more than one node wants from the same media file, defined once.
 *
 * <p>
 * An {@link ArtifactKey} is a contract between node implementations rather than a private name, so the key, the way the artifact is produced and the
 * way it is weighed all have to agree between the nodes that share it. Two nodes each declaring their own {@code "media/image"} key would be two
 * artifacts, decoded twice, and nobody would notice. Declaring the shared ones here is what keeps that from happening.
 * </p>
 *
 * <p>
 * Nothing here changes what a node does when it runs alone: outside a segment the scope is {@link ArtifactCache#noop()} and the decode happens per
 * node exactly as it did before.
 * </p>
 */
public final class MediaArtifacts {

	/**
	 * The media file decoded by {@code ImageIO} — what every image node starts from.
	 *
	 * <p>
	 * There are no parameters to encode in the id because there are none: this is the file as {@code ImageIO} reads it, at full resolution. A node
	 * wanting something derived — resized, colour-converted, cropped to a region — must publish that under its own key rather than mutating this one,
	 * which every other node in the segment is looking at.
	 * </p>
	 */
	public static final ArtifactKey<BufferedImage> DECODED_IMAGE = ArtifactKey.of("media/image", BufferedImage.class);

	/** Distinguishes "no reader could decode this" from a genuine read error, which the two callers report differently. */
	private static final class Undecodable extends IOException {

		private static final long serialVersionUID = 1L;
	}

	private MediaArtifacts() {
	}

	/**
	 * The decoded media file, produced once per segment however many nodes ask for it.
	 *
	 * <p>
	 * <strong>Read-only.</strong> The next node gets this same {@code BufferedImage}; drawing into it changes what everyone downstream measures.
	 * </p>
	 *
	 * @return the decoded image, or {@code null} when no reader could decode the file. A failed decode is never cached — the next node asking gets a
	 *         fresh attempt rather than an inherited verdict.
	 * @throws IOException
	 *             when the file could not be read
	 */
	public static BufferedImage decodedImageOrNull(NodeContext<LoomMedia> ctx) throws IOException {
		try {
			return ctx.artifacts().get(DECODED_IMAGE, () -> {
				BufferedImage image = ImageIO.read(ctx.media().file());
				if (image == null) {
					throw new Undecodable();
				}
				return Artifact.of(image, weigh(image));
			});
		} catch (ArtifactException e) {
			if (e.getCause() instanceof Undecodable) {
				return null;
			}
			if (e.getCause() instanceof IOException io) {
				throw io;
			}
			throw e;
		}
	}

	/**
	 * As {@link #decodedImageOrNull(NodeContext)}, for a node that treats an undecodable image as a failure rather than as a verdict.
	 *
	 * @throws IOException
	 *             when the file could not be read or no reader could decode it
	 */
	public static BufferedImage decodedImage(NodeContext<LoomMedia> ctx) throws IOException {
		BufferedImage image = decodedImageOrNull(ctx);
		if (image == null) {
			throw new IOException("No image reader could decode " + ctx.media().absolutePath());
		}
		return image;
	}

	/**
	 * What holding a decoded image costs.
	 *
	 * <p>
	 * Four bytes per pixel, which is exact for the {@code INT_*} types and a 33% over-estimate for the packed 3-byte ones. Over-estimating costs an
	 * eviction that was not strictly needed; under-estimating is how a worker runs out of memory, so the rounding goes this way on purpose.
	 * </p>
	 */
	private static long weigh(BufferedImage image) {
		return (long) image.getWidth() * image.getHeight() * 4L + 1024L;
	}
}
