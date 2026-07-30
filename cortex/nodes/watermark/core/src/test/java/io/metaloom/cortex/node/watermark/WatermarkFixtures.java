package io.metaloom.cortex.node.watermark;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * Synthetic images for the watermark tests.
 *
 * <p>
 * Generated rather than checked in, so an assertion can name the exact colour it expects at an exact coordinate. The base is a flat colour and the
 * overlay a different flat colour, which makes "did the overlay land in the right corner" a single {@code getRGB} comparison.
 * </p>
 */
public final class WatermarkFixtures {

	/** The base image's colour. Deliberately not a primary, so a channel swap would be visible. */
	public static final Color BASE_COLOUR = new Color(20, 40, 60);

	/** The overlay's colour, fully opaque. */
	public static final Color MARK_COLOUR = new Color(240, 10, 120);

	private WatermarkFixtures() {
	}

	/**
	 * A flat {@link #BASE_COLOUR} image.
	 *
	 * @param width  image width
	 * @param height image height
	 * @return the image
	 */
	public static BufferedImage baseImage(int width, int height) {
		return flat(width, height, BASE_COLOUR, BufferedImage.TYPE_INT_RGB);
	}

	/**
	 * A flat, fully opaque {@link #MARK_COLOUR} overlay with an alpha channel.
	 *
	 * @param width  overlay width
	 * @param height overlay height
	 * @return the overlay
	 */
	public static BufferedImage markImage(int width, int height) {
		return flat(width, height, MARK_COLOUR, BufferedImage.TYPE_INT_ARGB);
	}

	/**
	 * The overlay of {@link #markImage} as bare base64 PNG - what the {@code watermarkBase64} option holds.
	 *
	 * @param width  overlay width
	 * @param height overlay height
	 * @return base64-encoded PNG
	 * @throws IOException when PNG encoding fails
	 */
	public static String markBase64(int width, int height) throws IOException {
		return Base64.getEncoder().encodeToString(pngBytes(markImage(width, height)));
	}

	/**
	 * Encode an image as PNG.
	 *
	 * @param image the image
	 * @return the PNG bytes
	 * @throws IOException when PNG encoding fails
	 */
	public static byte[] pngBytes(BufferedImage image) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", bos)) {
			throw new IOException("No PNG writer available");
		}
		return bos.toByteArray();
	}

	private static BufferedImage flat(int width, int height, Color colour, int type) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(colour);
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
		return image;
	}
}
