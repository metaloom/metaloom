package io.metaloom.cortex.node.sam2;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Shared fixtures for the SAM 2 tests: real binary mask PNGs, the sidecar responses that carry them,
 * and upstream detection elements in exactly the encoding {@code ObjectDetectNode} emits.
 *
 * <p>
 * The masks are genuine 8-bit grayscale PNGs rather than arbitrary bytes, for the reason
 * {@code DepthmapTestFixtures} builds a real 16-bit one: the whole point of the artifact is that a
 * consumer can decode it as {@code TYPE_BYTE_GRAY} and read 0 or 255, and a fixture of fake bytes
 * would never catch a regression in that contract.
 * </p>
 *
 * <p>
 * The detection element is likewise a copy of the real upstream shape, so a change to
 * {@code ObjectDetectNode.detectionElements} breaks this test rather than production.
 * </p>
 */
final class Sam2TestFixtures {

	static final String MODEL = "facebook/sam2.1-hiera-small";

	private Sam2TestFixtures() {
	}

	/**
	 * An 8-bit grayscale PNG that is 255 inside the given rectangle and 0 everywhere else.
	 *
	 * @param width  mask width
	 * @param height mask height
	 * @param x      left edge of the set region
	 * @param y      top edge of the set region
	 * @param w      width of the set region
	 * @param h      height of the set region
	 * @return the encoded PNG bytes
	 */
	static byte[] binaryMaskPng(int width, int height, int x, int y, int w, int h) throws IOException {
		BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		WritableRaster raster = mask.getRaster();
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				boolean inside = px >= x && px < x + w && py >= y && py < y + h;
				raster.setSample(px, py, 0, inside ? 255 : 0);
			}
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(mask, "png", bos);
		return bos.toByteArray();
	}

	/** One entry of a {@code /v1/segment} response's {@code masks} array. */
	static JsonObject mask(int index, byte[] png, int x, int y, int w, int h, double score, String label) {
		JsonObject mask = new JsonObject()
			.put("index", index)
			.put("png_b64", Base64.getEncoder().encodeToString(png))
			.put("area", w * h)
			.put("score", score)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h));
		if (label != null) {
			mask.put("label", label).put("promptIndex", index);
		}
		return mask;
	}

	/** A {@code /v1/segment} response wrapping the given masks. */
	static JsonObject segmentResponse(Sam2Mode mode, int width, int height, List<JsonObject> masks, int droppedMasks) {
		return new JsonObject()
			.put("model", MODEL)
			.put("mode", mode.name())
			.put("width", width)
			.put("height", height)
			.put("masks", new JsonArray(masks))
			.put("truncated", new JsonObject().put("masks", droppedMasks));
	}

	/**
	 * A {@code /v1/track} response: one mask per named source frame, all for object 1.
	 *
	 * @param frameNumbers the <em>source</em> frame numbers, as the sidecar echoes them back
	 */
	static JsonObject trackResponse(int width, int height, List<Integer> frameNumbers, byte[] png) {
		JsonArray frames = new JsonArray();
		for (int i = 0; i < frameNumbers.size(); i++) {
			frames.add(new JsonObject()
				.put("index", i)
				.put("frameNumber", frameNumbers.get(i))
				.put("masks", new JsonArray().add(new JsonObject()
					.put("objId", 1)
					.put("png_b64", Base64.getEncoder().encodeToString(png))
					.put("area", 100)
					.put("bbox", new JsonObject().put("x", 1).put("y", 2).put("w", 10).put("h", 10)))));
		}
		return new JsonObject()
			.put("model", MODEL)
			.put("mode", "TRACK")
			.put("width", width)
			.put("height", height)
			.put("frameCount", frameNumbers.size())
			.put("objects", new JsonArray().add(new JsonObject().put("objId", 1).put("label", "person")))
			.put("frames", frames)
			.put("truncated", new JsonObject().put("frames", 0).put("masks", 0));
	}

	/**
	 * An upstream detection element in exactly {@code ObjectDetectNode}'s encoding — XYWH,
	 * {@code ABSOLUTE_PIXELS}, carrying the dimensions it was measured against.
	 */
	static String detectionElement(int index, String label, int x, int y, int w, int h,
		int imageWidth, int imageHeight, int frame) {
		return new JsonObject()
			.put("index", index)
			.put("type", "object")
			.put("label", label)
			.put("frame", frame)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 0.87d)
			.put("coordinates", "ABSOLUTE_PIXELS")
			.put("imageWidth", imageWidth)
			.put("imageHeight", imageHeight)
			.put("classId", 14)
			.encode();
	}

	/** The same box expressed 0..1, to prove the NORMALIZED guard rescales it to the same pixels. */
	static String normalizedDetectionElement(int index, String label, double x, double y, double w, double h,
		int imageWidth, int imageHeight) {
		return new JsonObject()
			.put("index", index)
			.put("type", "object")
			.put("label", label)
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 0.87d)
			.put("coordinates", "NORMALIZED")
			.put("imageWidth", imageWidth)
			.put("imageHeight", imageHeight)
			.encode();
	}
}
