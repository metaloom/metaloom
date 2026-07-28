package io.metaloom.cortex.node.depthmap;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * Image pre-processing for the depth sidecar call: read, downscale and encode as base64 PNG.
 *
 * <p>
 * Everything here is plain ImageIO / Graphics2D so the depthmap node stays free of the OpenCV native runtime that the video4j-backed nodes need. That
 * keeps the node - and its tests - runnable on any machine. It deliberately duplicates the handful of methods {@code VlmImages} offers rather than
 * depending on the VLM node module: one node reaching into another node's jar for two helpers is a worse trade than forty lines of ImageIO.
 * </p>
 *
 * <p>
 * PNG rather than the JPEG {@code VlmImages} produces: JPEG's blocking artifacts become spurious depth discontinuities along object edges, which is
 * exactly where the downstream {@code scene-layout} node samples.
 * </p>
 */
public final class DepthImages {

	private DepthImages() {
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
	 * Scale the image so its longest side is at most {@code maxDim}. Images that are already small enough are returned unchanged - upscaling costs
	 * inference time without adding detail.
	 *
	 * @param image  the source image
	 * @param maxDim target for the longest side in pixels; values &lt;= 0 disable scaling
	 * @return the scaled image, or the original instance when no scaling was needed
	 */
	public static BufferedImage downscale(BufferedImage image, int maxDim) {
		int longest = Math.max(image.getWidth(), image.getHeight());
		if (maxDim <= 0 || longest <= maxDim) {
			return image;
		}
		double factor = (double) maxDim / longest;
		int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(image.getHeight() * factor));

		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = scaled.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(image, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return scaled;
	}

	/**
	 * Encode the image as base64 PNG. Images carrying an alpha channel are flattened onto white first, so a transparent background does not reach the
	 * model as an arbitrary colour.
	 *
	 * @param image the image to encode
	 * @return the base64-encoded PNG bytes
	 * @throws IOException when PNG encoding fails
	 */
	public static String toBase64Png(BufferedImage image) throws IOException {
		BufferedImage opaque = toOpaque(image);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (!ImageIO.write(opaque, "png", bos)) {
			throw new IOException("No PNG writer available");
		}
		return Base64.getEncoder().encodeToString(bos.toByteArray());
	}

	private static BufferedImage toOpaque(BufferedImage image) {
		if (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
			return image;
		}
		BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = opaque.createGraphics();
		try {
			g.setColor(java.awt.Color.WHITE);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.drawImage(image, 0, 0, null);
		} finally {
			g.dispose();
		}
		return opaque;
	}
}
