package io.metaloom.cortex.runtime;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.preview.ImagePreviews;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.model.NodePreview;
import io.metaloom.loom.pipeline.model.PortPayload;

/**
 * Renders a node's image outputs small enough to travel back to Loom.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>
 * An {@code artifact/image} port carries a <em>path on the worker that produced it</em> —
 * {@code ThumbnailNode} and {@code ImageManipulationNode} both call
 * {@code ctx.output(OUT_IMAGE, path.toString())}. Loom cannot reach that filesystem, so the
 * debugging view could name a file nobody could look at. Producing the preview here, on the machine
 * that holds the bytes, is the only place it can be done at all.
 * </p>
 *
 * <h2>What it will not do</h2>
 *
 * <ul>
 * <li><strong>Nothing at all unless asked.</strong> Gated on {@code NodeTask.capturePreviews}, so a
 * production run over 100 000 files never opens an image it was not going to open anyway.</li>
 * <li><strong>Never fail a task.</strong> A preview is a convenience; a node that did its real work
 * correctly must not be reported as failed because a thumbnail could not be encoded. Every failure
 * here becomes a {@code skippedReason} the UI can show.</li>
 * <li><strong>Never truncate.</strong> Past the byte cap the preview is dropped, because half a JPEG
 * is not a smaller JPEG.</li>
 * <li><strong>Never follow a path off this machine.</strong> Only a value that is already a readable
 * local file is opened. A URI, a hash or a caption is left alone.</li>
 * </ul>
 */
public final class NodePreviews {

	private static final Logger log = LoggerFactory.getLogger(NodePreviews.class);

	private NodePreviews() {
	}

	/**
	 * Build previews for whichever of a node's outputs can have one.
	 *
	 * @param payloads the node's outputs, keyed by output port id
	 * @return previews keyed by the same port ids; empty when nothing was previewable
	 */
	public static Map<String, NodePreview> build(Map<String, PortPayload> payloads) {
		if (payloads == null || payloads.isEmpty()) {
			return Map.of();
		}
		int maxBytes = maxBytes();
		Map<String, NodePreview> previews = new LinkedHashMap<>();
		payloads.forEach((portId, payload) -> {
			if (!isImagePort(payload)) {
				return;
			}
			// Only the first element: a MANY image port would otherwise multiply the row by its
			// element count, and the strip shows one thumbnail per port regardless.
			Object value = firstValue(payload);
			if (!(value instanceof String path) || path.isBlank()) {
				return;
			}
			NodePreview preview = fromPath(path, maxBytes);
			if (preview != null) {
				previews.put(portId, preview);
			}
		});
		return previews;
	}

	/**
	 * Fold a node's own previews onto the ones generated from its payloads.
	 *
	 * <p>
	 * A port can end up with both an image and Markdown, and neither displaces the other: a node that
	 * produced an image <em>and</em> described what it did should not have to choose which the reader
	 * gets. Where a node authored an image for a port that also generated one, the node's wins — it
	 * knows what it meant to show, and the generated one is only ever a guess made from a file path.
	 * </p>
	 *
	 * <p>
	 * Keys that are not port ids pass straight through. That is how per-element previews
	 * ({@code portId#seq}) survive: nothing generates them, so there is never anything to fold them
	 * onto.
	 * </p>
	 *
	 * @param generated the image previews built from the payloads
	 * @param authored  previews from {@code ctx.preview(...)}, keyed by port id or {@code portId#seq}
	 */
	public static Map<String, NodePreview> merge(Map<String, NodePreview> generated, Map<String, NodePreview> authored) {
		if (authored == null || authored.isEmpty()) {
			return generated;
		}
		Map<String, NodePreview> merged = new LinkedHashMap<>(generated);
		authored.forEach((key, preview) -> {
			if (preview == null) {
				return;
			}
			NodePreview existing = merged.get(key);
			if (existing == null || existing.getData() == null) {
				merged.put(key, preview);
				return;
			}
			// Both carry an image: keep the node's, but do not lose a description that only the
			// generated side happened to have.
			if (preview.getData() != null) {
				merged.put(key, preview.getMarkdown() == null && existing.getMarkdown() != null
					? preview.withMarkdown(existing.getMarkdown())
					: preview);
			} else if (preview.getMarkdown() != null) {
				merged.put(key, existing.withMarkdown(preview.getMarkdown()));
			}
		});
		return merged;
	}

	/** Whether this payload's declared type says it carries an image. */
	private static boolean isImagePort(PortPayload payload) {
		String type = payload == null ? null : payload.getContentType();
		return ContentTypeRegistry.ARTIFACT_IMAGE.equals(type) || ContentTypeRegistry.MEDIA_IMAGE.equals(type);
	}

	private static Object firstValue(PortPayload payload) {
		if (payload.getElements() == null || payload.getElements().isEmpty()) {
			return null;
		}
		return payload.getElements().get(0).getValue();
	}

	/**
	 * @return the preview, a skip carrying its reason, or null when the value is not a local file at
	 *         all (in which case there is nothing to report — the port simply is not previewable)
	 */
	private static NodePreview fromPath(String rawPath, int maxBytes) {
		Path path;
		try {
			path = Path.of(rawPath);
		} catch (InvalidPathException e) {
			// A URI, most likely (s3://…). Not an error, just not a local file.
			return null;
		}
		if (!Files.isReadable(path) || Files.isDirectory(path)) {
			return null;
		}

		try {
			BufferedImage source = ImageIO.read(path.toFile());
			if (source == null) {
				// A readable file that is not an image ImageIO understands. The port said it was
				// one, so this is worth surfacing rather than silently skipping.
				return NodePreview.skipped("Not a readable image");
			}
			// Everything past the decode — downsample, encode, cap — is the shared policy, so that a
			// node building its own preview and the runtime building one from a path cannot disagree
			// about how big a preview is allowed to be.
			return ImagePreviews.fromImage(source, NodePreview.MAX_EDGE_PX, maxBytes);
		} catch (Exception e) {
			// Includes OutOfMemoryError's friendlier relatives: a malformed header can make ImageIO
			// try to allocate an enormous raster. Whatever went wrong, the node's real work already
			// succeeded and must not be recharacterised as a failure.
			log.debug("Could not build a preview for {}", rawPath, e);
			return NodePreview.skipped("Preview failed: " + e.getClass().getSimpleName());
		}
	}

	/**
	 * The byte ceiling, overridable per worker.
	 *
	 * @deprecated the policy moved to {@link ImagePreviews#maxBytes()}; kept because the tests and the
	 *             spec both name it here.
	 */
	static int maxBytes() {
		return ImagePreviews.maxBytes();
	}

	/** @see ImagePreviews#scaleToFit(BufferedImage, int) */
	static BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
		return ImagePreviews.scaleToFit(source, maxEdge);
	}
}
