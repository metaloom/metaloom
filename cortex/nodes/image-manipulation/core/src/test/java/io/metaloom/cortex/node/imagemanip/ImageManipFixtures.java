package io.metaloom.cortex.node.imagemanip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import io.metaloom.cortex.node.metadata.fixture.ExifJpegFixture;
import io.vertx.core.json.JsonObject;

/**
 * Test images whose geometry can be asserted by reading single pixels.
 *
 * <p>
 * Every fixture is built from flat, saturated quadrants rather than a photograph, so "did the crop take the top-left quarter" and "did the rotation turn
 * the right way" are colour comparisons instead of eyeballing. A photograph would make every assertion a threshold.
 * </p>
 */
public final class ImageManipFixtures {

	public static final Color TOP_LEFT = Color.RED;

	public static final Color TOP_RIGHT = Color.GREEN;

	public static final Color BOTTOM_LEFT = Color.BLUE;

	public static final Color BOTTOM_RIGHT = Color.YELLOW;

	private ImageManipFixtures() {
	}

	/**
	 * An image in four flat quadrants: red, green, blue, yellow clockwise from the top left.
	 *
	 * <p>
	 * Four different colours rather than two, because a 180° rotation and a horizontal mirror are indistinguishable on a two-colour image - and telling
	 * those apart is the whole point of the orientation tests.
	 * </p>
	 */
	public static BufferedImage quadrants(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			int halfW = width / 2;
			int halfH = height / 2;
			g.setColor(TOP_LEFT);
			g.fillRect(0, 0, halfW, halfH);
			g.setColor(TOP_RIGHT);
			g.fillRect(halfW, 0, width - halfW, halfH);
			g.setColor(BOTTOM_LEFT);
			g.fillRect(0, halfH, halfW, height - halfH);
			g.setColor(BOTTOM_RIGHT);
			g.fillRect(halfW, halfH, width - halfW, height - halfH);
		} finally {
			g.dispose();
		}
		return image;
	}

	/** A flat image with a fully transparent left half, for the JPEG alpha-flattening tests. */
	public static BufferedImage halfTransparent(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(TOP_LEFT);
			g.fillRect(width / 2, 0, width - width / 2, height);
		} finally {
			g.dispose();
		}
		return image;
	}

	/** Write an image to {@code tempDir} and return the file. */
	public static File writePng(File tempDir, String name, BufferedImage image) throws IOException {
		File file = new File(tempDir, name);
		ImageIO.write(image, "png", file);
		return file;
	}

	/**
	 * A real, decodable JPEG that also carries an EXIF {@code Orientation} tag.
	 *
	 * <p>
	 * Neither half exists on its own: {@code ImageIO} writes pixels but no EXIF, and {@link ExifJpegFixture} writes a reviewable EXIF block but
	 * deliberately no pixels ({@code SOI · APP1 · EOI}). Splicing the fixture's APP1 segment in behind the encoder's {@code SOI} gives a file that both
	 * {@code ImageIO.read} and metadata-extractor are happy with - which is exactly what an autorotate test needs.
	 * </p>
	 *
	 * @param exifOrientation the tag value, 1-8
	 */
	public static File writeJpegWithOrientation(File tempDir, String name, BufferedImage image, int exifOrientation) throws IOException {
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", encoded);
		byte[] jpeg = encoded.toByteArray();

		byte[] exifOnly = ExifJpegFixture.builder().orientation(exifOrientation).build();
		// exifOnly is SOI (2 bytes) + APP1 + EOI (2 bytes); take the APP1 segment out of the middle.
		byte[] app1 = new byte[exifOnly.length - 4];
		System.arraycopy(exifOnly, 2, app1, 0, app1.length);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(app1);
		out.write(jpeg, 2, jpeg.length - 2);

		File file = new File(tempDir, name);
		Files.write(file.toPath(), out.toByteArray());
		return file;
	}

	/** One detection element in the shape {@code FacedetectNode.OUT_DETECTIONS} emits. */
	public static String detection(int x, int y, int w, int h) {
		return detection(x, y, w, h, "face", 1.0d);
	}

	public static String detection(int x, int y, int w, int h, String type, double confidence) {
		return new JsonObject()
			.put("index", 0)
			.put("type", type)
			.put("label", type)
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", confidence)
			.put("coordinates", "ABSOLUTE_PIXELS")
			.encode();
	}
}
