package io.metaloom.cortex.node.sam2;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Image work for the SAM 2 node: read, downscale, encode for the sidecar, and composite the overlay.
 *
 * <p>
 * Plain ImageIO / Graphics2D, so every still-image path — and every test of it — runs without the
 * OpenCV natives the video path needs. It deliberately repeats the handful of methods
 * {@code DepthImages} offers rather than depending on the depthmap node's jar: one node reaching into
 * another for three helpers is a worse trade than the duplication.
 * </p>
 */
public final class Sam2Images {

	/** How strongly a mask tints the overlay. Enough to read the shape, light enough to see through. */
	private static final float OVERLAY_ALPHA = 0.45f;

	/**
	 * The tint cycle for overlay masks.
	 *
	 * <p>
	 * Fixed and cycled by index rather than random, so the same segmentation produces the same
	 * picture twice — a screenshot that changes colour on every regeneration is a diff nobody can
	 * review. Chosen to stay distinguishable next to each other and in greyscale print.
	 * </p>
	 */
	private static final Color[] OVERLAY_COLORS = {
		new Color(0xE6, 0x39, 0x46), new Color(0x2A, 0x9D, 0x8F), new Color(0xE9, 0xC4, 0x6A),
		new Color(0x45, 0x7B, 0x9D), new Color(0xF4, 0xA2, 0x61), new Color(0x8E, 0x7D, 0xBE),
		new Color(0x43, 0xAA, 0x8B), new Color(0xF9, 0x84, 0x4A)
	};

	private Sam2Images() {
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
	 * Scale the image so its longest side is at most {@code maxDim}. Images already small enough are
	 * returned unchanged — upscaling costs inference time without adding detail.
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
	 * Encode as base64 PNG for {@code /v1/segment}. Alpha is flattened onto white first, so a
	 * transparent background does not reach the model as an arbitrary colour.
	 *
	 * @param image the image to encode
	 * @return the base64-encoded PNG bytes
	 * @throws IOException when PNG encoding fails
	 */
	public static String toBase64Png(BufferedImage image) throws IOException {
		return encode(toOpaque(image), "png");
	}

	/**
	 * Encode as base64 JPEG for {@code /v1/track}.
	 *
	 * <p>
	 * JPEG, not PNG, and unlike the depthmap node this is not a compromise: SAM 2's own video
	 * predictor consumes JPEG frames, so those artefacts are what the model was evaluated against. The
	 * size argument is decisive anyway — 64 PNG frames would be five to ten times the request body.
	 * </p>
	 *
	 * @param image the frame to encode
	 * @return the base64-encoded JPEG bytes
	 * @throws IOException when JPEG encoding fails
	 */
	public static String toBase64Jpeg(BufferedImage image) throws IOException {
		return encode(toOpaque(image), "jpg");
	}

	private static String encode(BufferedImage image, String format) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (!ImageIO.write(image, format, bos)) {
			throw new IOException("No " + format.toUpperCase() + " writer available");
		}
		return Base64.getEncoder().encodeToString(bos.toByteArray());
	}

	/**
	 * Decode a base64-encoded image — a mask PNG coming back from the sidecar, or one of the JPEG
	 * frames this node encoded on the way out.
	 *
	 * @param imageB64 the base64 bytes
	 * @return the decoded image; a mask is 255 inside and 0 outside
	 * @throws IOException when the bytes hold no readable image
	 */
	public static BufferedImage decodeBase64(String imageB64) throws IOException {
		byte[] bytes = Base64.getDecoder().decode(imageB64);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) {
			throw new IOException("Could not decode a base64 image; no reader accepted the bytes");
		}
		return image;
	}

	/**
	 * Composite the masks over the frame as translucent tints — the picture of what was segmented.
	 *
	 * <p>
	 * This is the only automatic preview the node gets. {@code NodePreviews} takes the <em>first</em>
	 * element of a {@code MANY} port, so the {@code masks} port would render a segment-everything run
	 * as a single mask; the overlay is a {@code ONE} port and shows all of them at once.
	 * </p>
	 *
	 * @param frame the frame to draw on; masks are stretched to fit it if the sidecar returned them
	 *              at a different size (it clamps {@code max_dim} to its own cap, so it can)
	 * @param masks the decoded masks, in emission order — the tint cycles over that order
	 * @return a new image; the frame is not modified
	 */
	public static BufferedImage overlay(BufferedImage frame, List<BufferedImage> masks) {
		BufferedImage out = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.drawImage(frame, 0, 0, null);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OVERLAY_ALPHA));
			for (int i = 0; i < masks.size(); i++) {
				BufferedImage layer = tint(masks.get(i), OVERLAY_COLORS[i % OVERLAY_COLORS.length]);
				// Scaled rather than drawn at the origin: a size mismatch drawn 1:1 puts every mask in
				// the top-left corner, which looks like a segmentation failure rather than a scaling one.
				g.drawImage(layer, 0, 0, frame.getWidth(), frame.getHeight(), null);
			}
		} finally {
			g.dispose();
		}
		return out;
	}

	/**
	 * Turn a binary mask into an ARGB layer that is the colour where the mask is set and fully
	 * transparent everywhere else.
	 */
	private static BufferedImage tint(BufferedImage mask, Color color) {
		BufferedImage layer = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_INT_ARGB);
		int rgb = color.getRGB() & 0x00FFFFFF;
		for (int y = 0; y < mask.getHeight(); y++) {
			for (int x = 0; x < mask.getWidth(); x++) {
				// The sidecar writes 0 or 255, but a re-encoded mask can carry intermediate values along
				// the edge. Anything past the midpoint is inside.
				if ((mask.getRGB(x, y) & 0xFF) > 127) {
					layer.setRGB(x, y, 0xFF000000 | rgb);
				}
			}
		}
		return layer;
	}

	/**
	 * Write an image to disk as PNG, creating the parent directory.
	 *
	 * @param image  the image to write
	 * @param target where to write it
	 * @throws IOException when the directory or the file cannot be written
	 */
	public static void writePng(BufferedImage image, java.nio.file.Path target) throws IOException {
		java.nio.file.Files.createDirectories(target.getParent());
		if (!ImageIO.write(image, "png", target.toFile())) {
			throw new IOException("No PNG writer available for " + target);
		}
	}

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
