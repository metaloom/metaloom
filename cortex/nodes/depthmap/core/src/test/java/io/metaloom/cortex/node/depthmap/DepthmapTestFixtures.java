package io.metaloom.cortex.node.depthmap;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

import io.vertx.core.json.JsonObject;

/**
 * Shared fixtures for the depthmap tests: a real 16-bit grayscale PNG and the sidecar response
 * that carries it.
 *
 * <p>
 * Producing a genuine 16-bit PNG rather than arbitrary bytes matters - the whole point of the
 * artifact is that a consumer can decode it as {@code TYPE_USHORT_GRAY} and read
 * {@code 0..65535}, and a fixture of fake bytes would never catch a regression in that contract.
 * </p>
 */
final class DepthmapTestFixtures {

	static final String MODEL = "depth-anything/Depth-Anything-V2-Small-hf";

	private DepthmapTestFixtures() {
	}

	/**
	 * A 16-bit grayscale PNG whose left half is near (nearness 0.9) and right half far (0.1).
	 *
	 * @param width  map width
	 * @param height map height
	 * @return the encoded PNG bytes
	 */
	static byte[] gradientPng(int width, int height) throws IOException {
		BufferedImage map = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
		WritableRaster raster = map.getRaster();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				raster.setSample(x, y, 0, x < width / 2 ? (int) (0.9 * 65535) : (int) (0.1 * 65535));
			}
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(map, "png", bos);
		return bos.toByteArray();
	}

	/**
	 * The sidecar response wrapping the given map.
	 */
	static JsonObject response(byte[] png, int width, int height) {
		return new JsonObject()
			.put("model", MODEL)
			.put("convention", "NEARNESS")
			.put("source", "RELATIVE")
			.put("width", width)
			.put("height", height)
			.put("png_b64", Base64.getEncoder().encodeToString(png))
			.put("stats", new JsonObject().put("p05", 0.1d).put("p50", 0.5d).put("p95", 0.9d));
	}
}
