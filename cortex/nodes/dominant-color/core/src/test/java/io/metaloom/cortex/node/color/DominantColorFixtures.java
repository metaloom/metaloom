package io.metaloom.cortex.node.color;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Synthetic images and detection payloads for the dominant-colour tests.
 *
 * <p>
 * Everything is written as <strong>PNG</strong>, never JPEG. JPEG's chroma subsampling and DCT
 * ringing put colours into the decoded image that were never written to it, which would force every
 * expected value in these tests to become an approximation. With PNG the expected hex is exact, so
 * a one-channel regression fails the test instead of hiding inside a tolerance.
 * </p>
 *
 * <p>
 * File names end in {@code .png} because {@code FilterHelper.isImage} decides on the extension, and
 * a fixture the node refuses to process would make every test pass vacuously.
 * </p>
 */
public final class DominantColorFixtures {

	private DominantColorFixtures() {
	}

	/**
	 * @param dir  target directory
	 * @param name file name, ending in {@code .png}
	 * @param hex  the single colour
	 * @param w    width
	 * @param h    height
	 * @return the written file
	 * @throws IOException on write failure
	 */
	public static File writeSolid(File dir, String name, String hex, int w, int h) throws IOException {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int rgb = Rgb.ofHex(hex).packed();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setRGB(x, y, rgb);
			}
		}
		return write(dir, name, image);
	}

	/**
	 * A vertical split at {@code leftFraction} of the width.
	 *
	 * @param dir          target directory
	 * @param name         file name
	 * @param leftHex      colour of the left band
	 * @param rightHex     colour of the right band
	 * @param leftFraction fraction of the width taken by the left band
	 * @param w            width
	 * @param h            height
	 * @return the written file
	 * @throws IOException on write failure
	 */
	public static File writeSplit(File dir, String name, String leftHex, String rightHex, double leftFraction, int w, int h)
		throws IOException {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int left = Rgb.ofHex(leftHex).packed();
		int right = Rgb.ofHex(rightHex).packed();
		int boundary = (int) Math.round(w * leftFraction);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setRGB(x, y, x < boundary ? left : right);
			}
		}
		return write(dir, name, image);
	}

	/**
	 * Four equal quadrants, in reading order.
	 *
	 * @param dir   target directory
	 * @param name  file name
	 * @param hexes exactly four colours: top-left, top-right, bottom-left, bottom-right
	 * @param w     width, must be even
	 * @param h     height, must be even
	 * @return the written file
	 * @throws IOException on write failure
	 */
	public static File writeQuadrants(File dir, String name, String[] hexes, int w, int h) throws IOException {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] rgb = new int[4];
		for (int i = 0; i < 4; i++) {
			rgb[i] = Rgb.ofHex(hexes[i]).packed();
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int quadrant = (y < h / 2 ? 0 : 2) + (x < w / 2 ? 0 : 1);
				image.setRGB(x, y, rgb[quadrant]);
			}
		}
		return write(dir, name, image);
	}

	/**
	 * A vertical split whose right band is fully transparent. The right colour is still written
	 * into the RGB channels, so any implementation that flattens alpha onto a background instead of
	 * skipping it will report a blend rather than the left colour.
	 *
	 * @param dir            target directory
	 * @param name           file name
	 * @param opaqueHex      colour of the opaque left band
	 * @param transparentHex RGB of the fully transparent right band
	 * @param w              width
	 * @param h              height
	 * @return the written file
	 * @throws IOException on write failure
	 */
	public static File writeHalfTransparent(File dir, String name, String opaqueHex, String transparentHex, int w, int h)
		throws IOException {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		int opaque = 0xFF000000 | Rgb.ofHex(opaqueHex).packed();
		int transparent = Rgb.ofHex(transparentHex).packed();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setRGB(x, y, x < w / 2 ? opaque : transparent);
			}
		}
		return write(dir, name, image);
	}

	/**
	 * @param dir  target directory
	 * @param name file name
	 * @param hex  RGB written behind the zero alpha
	 * @param w    width
	 * @param h    height
	 * @return the written file
	 * @throws IOException on write failure
	 */
	public static File writeFullyTransparent(File dir, String name, String hex, int w, int h) throws IOException {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		int transparent = Rgb.ofHex(hex).packed();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setRGB(x, y, transparent);
			}
		}
		return write(dir, name, image);
	}

	/**
	 * Build a detections payload in the shape {@code FacedetectNode} emits.
	 *
	 * @param imageWidth  payload width, or null to omit the dimensions as the video path does
	 * @param imageHeight payload height, or null
	 * @param coordinates {@code ABSOLUTE_PIXELS} or {@code NORMALIZED}
	 * @param boxes       the detections
	 * @return the payload
	 */
	public static JsonObject detections(Integer imageWidth, Integer imageHeight, String coordinates, JsonObject... boxes) {
		JsonArray items = new JsonArray();
		for (JsonObject box : boxes) {
			items.add(box);
		}
		JsonObject payload = new JsonObject().put("coordinates", coordinates).put("detections", items);
		if (imageWidth != null && imageHeight != null) {
			payload.put("imageWidth", imageWidth).put("imageHeight", imageHeight);
		}
		return payload;
	}

	/**
	 * @param index detection index
	 * @param label detection label
	 * @param x     box left edge
	 * @param y     box top edge
	 * @param w     box width
	 * @param h     box height
	 * @return one detection entry
	 */
	public static JsonObject detection(int index, String label, double x, double y, double w, double h) {
		return new JsonObject()
			.put("index", index)
			.put("type", label)
			.put("label", label)
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 1.0d);
	}

	private static File write(File dir, String name, BufferedImage image) throws IOException {
		File file = new File(dir, name);
		ImageIO.write(image, "png", file);
		return file;
	}
}
