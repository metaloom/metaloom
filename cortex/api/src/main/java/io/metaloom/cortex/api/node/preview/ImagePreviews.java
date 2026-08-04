package io.metaloom.cortex.api.node.preview;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.pipeline.model.NodePreview;

/**
 * Turns an in-memory image into a {@link NodePreview}, applying the one downsample-and-cap policy
 * previews have.
 *
 * <p>
 * The worker runtime uses this for the images it finds by itself — any {@code artifact/image} port
 * whose value is a readable path — and a node uses it for images it can show but never wrote to
 * disk. Face detection is the case that forced it out into the open: the frame it examined and the
 * crops it cut from it exist only in memory, and both are exactly what someone debugging the node
 * wants to look at.
 * </p>
 *
 * <p>
 * It lives in {@code cortex-api} rather than in the runtime because a node must not depend on the
 * thing that runs it, and because the alternative — each node capping and encoding its own previews —
 * is how one node ends up shipping a 4 MB "preview".
 * </p>
 *
 * <p>
 * Three rules, inherited from the runtime's original implementation and unchanged:
 * </p>
 * <ul>
 * <li><strong>Never fail the node.</strong> A preview is a convenience. Anything that goes wrong here
 * becomes a skip carrying its reason, never an exception the node has to handle.</li>
 * <li><strong>Never truncate.</strong> Past the byte cap the preview is dropped, because half a JPEG
 * renders as a grey box that looks like a bug in the node rather than a missing preview.</li>
 * <li><strong>Never guess at alpha.</strong> The output is JPEG, which has none; flattening happens
 * here rather than being left to a writer plugin that may fail or invert the colours.</li>
 * </ul>
 */
public final class ImagePreviews {

	private static final Logger log = LoggerFactory.getLogger(ImagePreviews.class);

	/** Overrides {@link NodePreview#DEFAULT_MAX_BYTES} per worker. */
	public static final String MAX_BYTES_ENV = "CORTEX_PREVIEW_MAX_BYTES";

	private static final String PREVIEW_MIME = "image/jpeg";

	private static final float JPEG_QUALITY = 0.8f;

	private ImagePreviews() {
	}

	/**
	 * Downsample to {@link NodePreview#MAX_EDGE_PX} and encode, within the configured byte cap.
	 */
	public static NodePreview fromImage(BufferedImage source) {
		return fromImage(source, NodePreview.MAX_EDGE_PX, maxBytes());
	}

	/**
	 * Downsample to {@code maxEdge} and encode, within the configured byte cap.
	 *
	 * <p>
	 * A smaller {@code maxEdge} is what a per-element preview wants: a face crop shown at 40px in a
	 * result strip does not need the 512 a whole frame does, and a run that emits one per detected face
	 * multiplies whatever it chooses.
	 * </p>
	 */
	public static NodePreview fromImage(BufferedImage source, int maxEdge) {
		return fromImage(source, maxEdge, maxBytes());
	}

	/**
	 * @param source  the image; {@code null} yields {@code null}, so a caller that could not decode
	 *                anything does not have to branch
	 * @param maxEdge longest edge of the result, in pixels
	 * @param maxBytes ceiling for the encoded bytes
	 * @return the preview, or a skip carrying its reason; {@code null} only for a {@code null} source
	 */
	public static NodePreview fromImage(BufferedImage source, int maxEdge, int maxBytes) {
		if (source == null) {
			return null;
		}
		try {
			BufferedImage scaled = scaleToFit(source, maxEdge);
			byte[] encoded = encodeJpeg(scaled);
			if (encoded == null) {
				return NodePreview.skipped("Could not encode preview");
			}
			if (encoded.length > maxBytes) {
				return NodePreview.skipped("Preview exceeds " + maxBytes + " bytes");
			}
			return NodePreview.image(PREVIEW_MIME, scaled.getWidth(), scaled.getHeight(), encoded);
		} catch (Exception e) {
			// Includes OutOfMemoryError's friendlier relatives: a malformed header can make the
			// pipeline try to allocate an enormous raster. Whatever went wrong, the node's real work
			// already succeeded and must not be recharacterised as a failure.
			log.debug("Could not build a preview", e);
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
	public static BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
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

	/** Encode as JPEG at {@link #JPEG_QUALITY}, or {@code null} when no writer is available. */
	public static byte[] encodeJpeg(BufferedImage image) throws Exception {
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
		ImageOutputStream stream = ImageIO.createImageOutputStream(out);
		try {
			Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
			if (!writers.hasNext()) {
				return false;
			}
			ImageWriter writer = writers.next();
			ImageWriteParam param = writer.getDefaultWriteParam();
			if (param.canWriteCompressed()) {
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(JPEG_QUALITY);
			}
			writer.setOutput(stream);
			try {
				writer.write(null, new IIOImage(image, null, null), param);
			} finally {
				writer.dispose();
			}
			return true;
		} finally {
			stream.close();
		}
	}

	/** The byte ceiling, overridable per worker. A malformed value falls back to the default. */
	public static int maxBytes() {
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
