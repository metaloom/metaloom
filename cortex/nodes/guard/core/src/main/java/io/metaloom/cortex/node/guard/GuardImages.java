package io.metaloom.cortex.node.guard;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * Read, downscale and encode the image a guard model is asked about.
 *
 * <p>
 * Plain ImageIO and Graphics2D, so the node stays free of the OpenCV native runtime the video4j
 * nodes need and runs — and tests — anywhere. This mirrors {@code VlmImages} in the vlm node and
 * {@code captioning}'s own copy rather than sharing with them: a node reaching into a sibling node's
 * jar for three static methods would make the two modules depend on each other for no other reason.
 * </p>
 */
public final class GuardImages {

	private GuardImages() {
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
	 * Scale the image so its longest side is at most {@code maxDim}. Images already small enough come
	 * back untouched — upscaling would cost the model tokens without adding detail.
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
	 * Encode the image as a {@code data:image/jpeg;base64,...} URI, the form an OpenAI-compatible
	 * {@code image_url} content part expects.
	 *
	 * @param image the image to encode
	 * @return the data URI
	 * @throws IOException when JPEG encoding fails
	 */
	public static String toJpegDataUri(BufferedImage image) throws IOException {
		BufferedImage opaque = toOpaque(image);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (!ImageIO.write(opaque, "jpg", bos)) {
			throw new IOException("No JPEG writer available");
		}
		return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
	}

	/** The JPEG writer rejects an alpha channel, so a transparent image is flattened onto white first. */
	private static BufferedImage toOpaque(BufferedImage image) {
		if (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
			return image;
		}
		BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = opaque.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.drawImage(image, 0, 0, null);
		} finally {
			g.dispose();
		}
		return opaque;
	}
}
