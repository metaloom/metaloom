package io.metaloom.cortex.node.vlm;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * Image pre-processing for vision-language calls: read, downscale, rotate and encode as a JPEG data URI.
 *
 * <p>
 * Everything here is plain ImageIO / Graphics2D so the VLM node stays free of the OpenCV native runtime that the video4j-backed nodes need. That keeps
 * the node - and its tests - runnable on any machine.
 * </p>
 */
public final class VlmImages {

	private VlmImages() {
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
	 * Scale the image so its longest side is at most {@code maxDim}. Images that are already small enough are returned unchanged - upscaling would only
	 * cost the model tokens without adding detail.
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
	 * Rotate the image clockwise.
	 *
	 * @param image   the source image
	 * @param degrees 0, 90, 180 or 270; any other value returns the source unchanged
	 * @return the rotated image
	 */
	public static BufferedImage rotate(BufferedImage image, int degrees) {
		if (degrees != 90 && degrees != 180 && degrees != 270) {
			return image;
		}
		boolean swapsAxes = degrees == 90 || degrees == 270;
		int width = swapsAxes ? image.getHeight() : image.getWidth();
		int height = swapsAxes ? image.getWidth() : image.getHeight();

		BufferedImage rotated = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rotated.createGraphics();
		try {
			AffineTransform transform = new AffineTransform();
			// Move the rotated content back into the positive quadrant before turning it.
			transform.translate(width / 2.0, height / 2.0);
			transform.rotate(Math.toRadians(degrees));
			transform.translate(-image.getWidth() / 2.0, -image.getHeight() / 2.0);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, transform, null);
		} finally {
			g.dispose();
		}
		return rotated;
	}

	/**
	 * Encode the image as a {@code data:image/jpeg;base64,...} URI, the form the OpenAI-compatible {@code image_url} content part expects.
	 *
	 * @param image the image to encode
	 * @return the data URI
	 * @throws IOException when JPEG encoding fails
	 */
	public static String toJpegDataUri(BufferedImage image) throws IOException {
		return "data:image/jpeg;base64," + toBase64Jpeg(image);
	}

	/**
	 * Encode the image as base64 JPEG. Images carrying an alpha channel are flattened onto white first, because the JPEG writer rejects them.
	 */
	public static String toBase64Jpeg(BufferedImage image) throws IOException {
		BufferedImage opaque = toOpaque(image);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (!ImageIO.write(opaque, "jpg", bos)) {
			throw new IOException("No JPEG writer available");
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
