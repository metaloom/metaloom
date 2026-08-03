package io.metaloom.cortex.node.imagemanip;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Rect;
import io.vertx.core.json.JsonObject;

/**
 * Turning the {@code detections} input port's JSON elements into rectangles.
 *
 * <p>
 * The elements look like this, one per detected subject - see {@code FacedetectNode.OUT_DETECTIONS}:
 * </p>
 *
 * <pre>
 * { "index": 0, "type": "face", "label": "face", "frame": 0,
 *   "bbox": {"x":100,"y":50,"w":80,"h":80}, "confidence": 1.0,
 *   "coordinates": "ABSOLUTE_PIXELS",
 *   "imageWidth": 1920, "imageHeight": 1080 }
 * </pre>
 *
 * <p>
 * 🔴 <strong>The port is the only trustworthy source for these boxes.</strong> The same detections are also written to the {@code detection} table,
 * whose {@code bbox_x} column is documented as <em>"normalized 0-1. This is the single geometry convention"</em> while the node writes absolute pixels
 * into it and nothing validates either claim. Reading boxes back over REST would therefore crop from the wrong coordinate space by a factor of the
 * image width. The elements carry an explicit {@code coordinates} marker precisely so a consumer here never has to guess.
 * </p>
 */
public final class SubjectBoxes {

	/** What {@code FacedetectNode} stamps on every element. Anything else is a coordinate space this node cannot interpret. */
	private static final String ABSOLUTE_PIXELS = "ABSOLUTE_PIXELS";

	private SubjectBoxes() {
	}

	/**
	 * Parse and filter the detection elements into boxes in the source frame.
	 *
	 * <p>
	 * Everything unusable is dropped rather than raised: a malformed element among twenty good ones must not fail the item, and an empty result is a
	 * legitimate outcome the caller already has to handle (it is what {@code subjectFallback} exists for).
	 * </p>
	 *
	 * @param elements      the raw port elements
	 * @param acceptedTypes lowercase {@code type} values to keep; empty accepts every type
	 * @param minConfidence detections below this are dropped
	 * @param width         source frame width, used to clamp
	 * @param height        source frame height, used to clamp
	 * @return the boxes, in element order, each inside the frame; never null, possibly empty
	 */
	public static List<Rect> parse(List<String> elements, Set<String> acceptedTypes, double minConfidence, int width, int height) {
		List<Rect> boxes = new ArrayList<>();
		if (elements == null) {
			return boxes;
		}
		for (String element : elements) {
			Rect rect = parseOne(element, acceptedTypes, minConfidence, width, height);
			if (rect != null) {
				boxes.add(rect);
			}
		}
		return boxes;
	}

	private static Rect parseOne(String element, Set<String> acceptedTypes, double minConfidence, int width, int height) {
		if (element == null || element.isBlank()) {
			return null;
		}
		JsonObject json;
		try {
			json = new JsonObject(element);
		} catch (Exception e) {
			return null;
		}

		// An element with no explicit marker is treated as absolute pixels, which is what every producer in
		// the tree emits today. A marker naming anything else is a space this node cannot convert from, so
		// the box is dropped rather than silently misread.
		String coordinates = json.getString("coordinates", ABSOLUTE_PIXELS);
		if (!ABSOLUTE_PIXELS.equalsIgnoreCase(coordinates)) {
			return null;
		}

		if (!acceptedTypes.isEmpty()) {
			String type = json.getString("type", json.getString("label", ""));
			if (!acceptedTypes.contains(type.toLowerCase(Locale.ROOT))) {
				return null;
			}
		}

		Double confidence = json.getDouble("confidence");
		if (confidence != null && confidence < minConfidence) {
			return null;
		}

		JsonObject bbox = json.getJsonObject("bbox");
		if (bbox == null) {
			return null;
		}
		Integer x = bbox.getInteger("x");
		Integer y = bbox.getInteger("y");
		Integer w = bbox.getInteger("w");
		Integer h = bbox.getInteger("h");
		if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) {
			return null;
		}
		return ManipulationGeometry.clamp(new Rect(x, y, w, h), width, height);
	}

	/**
	 * Split the {@code subjectTypes} option into a lowercase set.
	 *
	 * @param value comma-separated types; blank or {@code *} means "accept every type"
	 */
	public static Set<String> types(String value) {
		Set<String> types = new LinkedHashSet<>();
		if (value == null || value.isBlank() || "*".equals(value.trim())) {
			return types;
		}
		for (String part : value.split(",")) {
			String type = part.trim().toLowerCase(Locale.ROOT);
			if (!type.isEmpty()) {
				types.add(type);
			}
		}
		return types;
	}

	/**
	 * A stable, order-sensitive digest of the boxes that will actually shape the crop.
	 *
	 * <p>
	 * 🔴 This is what goes into the cache key and the artifact file name. The detections are a <em>second</em> input that changes the output pixels, so
	 * without them a re-run against better face boxes would be served the first run's crop from the local cache. Only the surviving boxes are digested -
	 * an element that was filtered out cannot change the result and must not invalidate the cache.
	 * </p>
	 */
	public static String material(List<Rect> boxes) {
		StringBuilder builder = new StringBuilder();
		for (Rect rect : boxes) {
			builder.append(rect.x()).append(',').append(rect.y()).append(',')
				.append(rect.w()).append(',').append(rect.h()).append(';');
		}
		return builder.toString();
	}
}
