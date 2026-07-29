package io.metaloom.cortex.node.scenelayout;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.function.BiFunction;

import javax.imageio.ImageIO;

import io.vertx.core.json.JsonObject;

/**
 * Synthetic depth maps and detection payloads for the scene-layout tests.
 *
 * <p>
 * The whole reason {@link DepthSampler} and {@link RelationSolver} are pure is so the expected
 * predicates can be <em>exact</em> rather than plausible: a hand-built nearness field plus
 * hand-placed boxes has one correct answer, and no model, GPU or real photo is involved.
 * </p>
 */
final class SceneLayoutFixtures {

	static final String MODEL = "depth-anything/Depth-Anything-V2-Small-hf";

	private SceneLayoutFixtures() {
	}

	/**
	 * Write a 16-bit grayscale depth PNG whose nearness at each pixel comes from {@code field}.
	 *
	 * @param file   destination
	 * @param width  map width
	 * @param height map height
	 * @param field  (x, y) -&gt; nearness in [0,1], 1 = nearest
	 */
	static void writeMap(File file, int width, int height, BiFunction<Integer, Integer, Double> field) throws IOException {
		BufferedImage map = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
		WritableRaster raster = map.getRaster();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				double nearness = Math.max(0, Math.min(1, field.apply(x, y)));
				raster.setSample(x, y, 0, (int) Math.round(nearness * 65535));
			}
		}
		ImageIO.write(map, "png", file);
	}

	/**
	 * The canonical fixture: left half near (0.9), right half far (0.1).
	 */
	static void writeSplitMap(File file, int width, int height) throws IOException {
		writeMap(file, width, height, (x, y) -> x < width / 2 ? 0.9 : 0.1);
	}

	/**
	 * A left-to-right linear ramp from nearness 1.0 to 0.0.
	 *
	 * <p>
	 * The right fixture for banding tests. A bimodal split map has a degenerate distribution -
	 * its 0.66 quantile lands exactly <em>on</em> the near plateau - so band boundaries there sit
	 * on a 16-bit rounding artifact. A ramp puts the quantiles at their nominal values, which is
	 * also what a real depth map looks like.
	 * </p>
	 */
	static void writeRampMap(File file, int width, int height) throws IOException {
		writeMap(file, width, height, (x, y) -> 1.0 - (double) x / (width - 1));
	}

	/**
	 * A depth map that is uniform everywhere - nothing can be ordered within it.
	 */
	static void writeFlatMap(File file, int width, int height, double nearness) throws IOException {
		writeMap(file, width, height, (x, y) -> nearness);
	}

	/**
	 * The metadata the depthmap node emits for a map.
	 */
	static JsonObject depthMeta(File mapFile, int mapWidth, int mapHeight, int imageWidth, int imageHeight) {
		return new JsonObject()
			.put("model", MODEL)
			.put("convention", "NEARNESS")
			.put("source", "RELATIVE")
			.put("width", mapWidth)
			.put("height", mapHeight)
			.put("imageWidth", imageWidth)
			.put("imageHeight", imageHeight)
			.put("path", mapFile.getAbsolutePath());
	}

	/**
	 * One detection entry - a self-contained element of the {@link SceneLayoutNode#IN_DETECTIONS}
	 * port. There is no batch wrapper any more: the port is {@code MANY}, one element per detection.
	 */
	static JsonObject detection(int index, String label, double x, double y, double w, double h) {
		return new JsonObject()
			.put("index", index)
			.put("type", label)
			.put("label", label)
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 1.0d);
	}

	/**
	 * Build a {@link LayoutObject} directly, for solver tests that do not need a map at all.
	 */
	static LayoutObject object(String id, BoxF box, double near, double spread) {
		DepthStats stats = new DepthStats(near, near - spread / 2, near + spread / 2, 1000);
		return new LayoutObject(id, "face", "face", "facedetect", box, box, 1.0, stats, null);
	}
}
