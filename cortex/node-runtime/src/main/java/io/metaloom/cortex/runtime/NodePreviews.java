package io.metaloom.cortex.runtime;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	/** Environment override for the byte ceiling, for an operator with a different tolerance. */
	private static final String MAX_BYTES_ENV = "CORTEX_PREVIEW_MAX_BYTES";

	/**
	 * Quality of the re-encoded JPEG.
	 *
	 * <p>
	 * Chosen against the byte cap rather than for fidelity: at 512px on the longest edge this keeps a
	 * typical photograph comfortably inside 96 KiB, which is the difference between previews working
	 * and previews being dropped for being too big.
	 * </p>
	 */
	private static final float JPEG_QUALITY = 0.8f;

	private static final String PREVIEW_MIME = "image/jpeg";

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
	 * Fold a node's own Markdown descriptions onto the generated image previews.
	 *
	 * <p>
	 * A port can end up with both, and neither displaces the other: a node that produced an image
	 * <em>and</em> described what it did should not have to choose which the reader gets. A port with
	 * only Markdown gets a Markdown-only preview; a port with only an image is left as it was.
	 * </p>
	 *
	 * @param generated the image previews built from the payloads
	 * @param authored  Markdown per output port id, from {@code ctx.preview(...)}
	 */
	public static Map<String, NodePreview> merge(Map<String, NodePreview> generated, Map<String, String> authored) {
		if (authored == null || authored.isEmpty()) {
			return generated;
		}
		Map<String, NodePreview> merged = new LinkedHashMap<>(generated);
		authored.forEach((portId, markdown) -> {
			if (markdown == null || markdown.isBlank()) {
				return;
			}
			NodePreview existing = merged.get(portId);
			merged.put(portId, existing == null ? NodePreview.markdown(markdown) : existing.withMarkdown(markdown));
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
			BufferedImage scaled = scaleToFit(source, NodePreview.MAX_EDGE_PX);
			byte[] encoded = encodeJpeg(scaled);
			if (encoded == null) {
				return NodePreview.skipped("Could not encode preview");
			}
			if (encoded.length > maxBytes) {
				return NodePreview.skipped("Preview exceeds " + maxBytes + " bytes");
			}
			return NodePreview.image(PREVIEW_MIME, scaled.getWidth(), scaled.getHeight(), encoded);
		} catch (Exception e) {
			// Includes OutOfMemoryError's friendlier relatives: a malformed header can make ImageIO
			// try to allocate an enormous raster. Whatever went wrong, the node's real work already
			// succeeded and must not be recharacterised as a failure.
			log.debug("Could not build a preview for {}", rawPath, e);
			return NodePreview.skipped("Preview failed: " + e.getClass().getSimpleName());
		}
	}

	/**
	 * Scale so the longest edge is at most {@code maxEdge}, preserving aspect ratio.
	 *
	 * <p>
	 * An image already within the bound is returned untouched rather than re-encoded at a nominal
	 * "scale of 1", which would cost a full redraw to produce the same pixels.
	 * </p>
	 */
	static BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
		int width = source.getWidth();
		int height = source.getHeight();
		if (width <= maxEdge && height <= maxEdge) {
			return source;
		}
		double scale = (double) maxEdge / Math.max(width, height);
		// Never round down to zero: a 4000x3 panorama strip still has to have a height.
		int targetWidth = Math.max(1, (int) Math.round(width * scale));
		int targetHeight = Math.max(1, (int) Math.round(height * scale));

		// TYPE_INT_RGB, not ARGB: the output is JPEG, which has no alpha channel. Handing a
		// transparent source to the JPEG writer produces either a failure or inverted colours
		// depending on the plugin, so the flattening is done here, deliberately.
		BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
		} finally {
			g.dispose();
		}
		return target;
	}

	private static byte[] encodeJpeg(BufferedImage image) throws Exception {
		BufferedImage opaque = image;
		if (image.getColorModel().hasAlpha()) {
			// Reached when the source was already small enough to skip scaleToFit, so it was
			// returned as-is and may still carry alpha.
			opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = opaque.createGraphics();
			try {
				g.drawImage(image, 0, 0, null);
			} finally {
				g.dispose();
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!writeJpeg(opaque, out)) {
			return null;
		}
		return out.toByteArray();
	}

	private static boolean writeJpeg(BufferedImage image, ByteArrayOutputStream out) throws Exception {
		javax.imageio.stream.ImageOutputStream stream = ImageIO.createImageOutputStream(out);
		try {
			java.util.Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
			if (!writers.hasNext()) {
				return false;
			}
			javax.imageio.ImageWriter writer = writers.next();
			javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
			if (param.canWriteCompressed()) {
				param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(JPEG_QUALITY);
			}
			writer.setOutput(stream);
			try {
				writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
			} finally {
				writer.dispose();
			}
			return true;
		} finally {
			stream.close();
		}
	}

	/** The byte ceiling, overridable per worker. A malformed value falls back to the default. */
	static int maxBytes() {
		String configured = System.getenv(MAX_BYTES_ENV);
		if (configured == null || configured.isBlank()) {
			return NodePreview.DEFAULT_MAX_BYTES;
		}
		try {
			int value = Integer.parseInt(configured.trim());
			return value > 0 ? value : NodePreview.DEFAULT_MAX_BYTES;
		} catch (NumberFormatException e) {
			log.warn("Ignoring unparseable {}='{}'", MAX_BYTES_ENV, configured);
			return NodePreview.DEFAULT_MAX_BYTES;
		}
	}
}
