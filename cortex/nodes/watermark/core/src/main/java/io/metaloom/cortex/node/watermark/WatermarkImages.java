package io.metaloom.cortex.node.watermark;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import io.metaloom.cortex.node.watermark.WatermarkGeometry.Placement;

/**
 * Decoding and compositing for the watermark node's image path.
 *
 * <p>
 * Everything here is plain ImageIO / Graphics2D so the watermark node stays free of the OpenCV native runtime that the video4j-backed nodes need - the
 * same trade {@code DepthImages} and {@code VlmImages} make, and for the same reason: the node and its tests stay runnable on any machine.
 * </p>
 *
 * <p>
 * PNG output throughout. The overlay almost always carries an alpha channel, and PNG is both lossless and byte-deterministic, which is what lets the
 * node's tests assert on individual pixels of the result.
 * </p>
 */
public final class WatermarkImages {

	/** Accepts either a bare base64 payload or a full {@code data:image/png;base64,...} URI, which is what a browser's file picker produces. */
	private static final String DATA_URI_MARKER = ";base64,";

	private WatermarkImages() {
	}

	/**
	 * Decode a base64-encoded watermark image.
	 *
	 * @param base64 the payload, optionally prefixed with a {@code data:} URI header
	 * @return the decoded image, alpha channel preserved
	 * @throws IOException when the string is not valid base64, or holds no image ImageIO understands
	 */
	public static BufferedImage decode(String base64) throws IOException {
		if (base64 == null || base64.isBlank()) {
			throw new IOException("No watermark image configured");
		}
		String payload = strip(base64);
		byte[] bytes;
		try {
			bytes = Base64.getMimeDecoder().decode(payload);
		} catch (IllegalArgumentException e) {
			throw new IOException("Watermark image is not valid base64: " + e.getMessage(), e);
		}
		BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
		if (image == null) {
			throw new IOException("No image reader could decode the configured watermark (" + bytes.length + " bytes)");
		}
		return image;
	}

	/**
	 * Strip a {@code data:} URI header and any embedded whitespace. The MIME decoder tolerates line breaks, but a pipeline definition edited by hand can
	 * also contain spaces, which it does not.
	 */
	static String strip(String base64) {
		String payload = base64.trim();
		int marker = payload.indexOf(DATA_URI_MARKER);
		if (marker >= 0) {
			payload = payload.substring(marker + DATA_URI_MARKER.length());
		}
		return payload.replace(" ", "");
	}

	/**
	 * Read an image file.
	 *
	 * @param file the image to read
	 * @return the decoded image
	 * @throws IOException when the file cannot be read or holds no image ImageIO understands
	 */
	public static BufferedImage read(File file) throws IOException {
		BufferedImage image = ImageIO.read(file);
		if (image == null) {
			throw new IOException("No image reader could decode " + file.getAbsolutePath());
		}
		return image;
	}

	/**
	 * Draw the watermark over a copy of the base image.
	 *
	 * <p>
	 * The base is copied into a fresh {@code TYPE_INT_ARGB} raster rather than drawn onto in place: {@code ImageIO.read} may hand back an image backed by
	 * a read-only or indexed raster, and compositing onto an indexed palette silently quantises the overlay.
	 * </p>
	 *
	 * @param base      the media frame
	 * @param watermark the overlay, drawn scaled into {@code placement}
	 * @param placement where and how large, from {@link WatermarkGeometry#place}
	 * @param opacity   overlay alpha, <code>0.0</code>-<code>1.0</code>; <code>1.0</code> uses the overlay's own alpha unchanged
	 * @return a new image; neither argument is modified
	 */
	public static BufferedImage composite(BufferedImage base, BufferedImage watermark, Placement placement, double opacity) {
		BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = result.createGraphics();
		try {
			g.drawImage(base, 0, 0, null);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			float alpha = (float) Math.max(0d, Math.min(1d, opacity));
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			g.drawImage(watermark, placement.x(), placement.y(), placement.width(), placement.height(), null);
		} finally {
			g.dispose();
		}
		return result;
	}

	/**
	 * Write the image as a PNG, atomically: the bytes land in a sibling {@code .part} file that is then moved into place, so a crashed or killed worker
	 * can never leave a truncated artifact that a later run would serve from cache.
	 *
	 * @param image  the image to write
	 * @param target the destination path; its parent directory is created if missing
	 * @throws IOException when the directory cannot be created or PNG encoding fails
	 */
	public static void writePng(BufferedImage image, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		Path part = AtomicFiles.partFor(target);
		try {
			if (!ImageIO.write(image, "png", part.toFile())) {
				throw new IOException("No PNG writer available");
			}
			AtomicFiles.move(part, target);
		} finally {
			Files.deleteIfExists(part);
		}
	}
}
