package io.metaloom.cortex.node.color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonObject;

/**
 * Turns the node's configuration plus whatever the upstream detectors emitted into the ordered list
 * of regions to measure.
 *
 * <p>
 * It takes the raw upstream map rather than a {@code NodeContext} so it can be exercised without
 * constructing a context - the same reason the scene-layout node keeps its relation solver pure.
 * </p>
 *
 * <h2>There is no precedence between the three sources</h2>
 *
 * All enabled sources are merged into one list. They answer different questions - "what colour is
 * this photo", "what colour is the logo area", "what colour is this person wearing" - and a
 * consumer may legitimately want all three at once. The emission order is part of the contract:
 * whole image, then the configured region, then detections in configured source order and within a
 * source in payload index order.
 */
public class RegionResolver {

	private static final Logger log = LoggerFactory.getLogger(RegionResolver.class);

	/**
	 * @param regions   the regions to measure, in contract order
	 * @param dropped   how many detections were discarded as unusable
	 * @param truncated how many detections were discarded by the {@code maxRegions} cap
	 */
	public record Resolution(List<RegionSource> regions, int dropped, int truncated) {
	}

	/** What a detection-derived region records as its origin now that no node id is involved. */
	static final String DETECTIONS_SOURCE = "detections";

	private final DominantColorNodeOptions options;

	public RegionResolver(DominantColorNodeOptions options) {
		this.options = options;
	}

	/**
	 * @param detections  the encoded detection elements wired into the node, seq-ordered
	 * @param imageWidth  the decoded image width
	 * @param imageHeight the decoded image height
	 * @return the resolved regions
	 */
	public Resolution resolve(List<String> detections, int imageWidth, int imageHeight) {
		List<RegionSource> regions = new ArrayList<>();
		int dropped = 0;

		if (options.isIncludeWholeImage()) {
			regions.add(RegionSource.wholeImage(new Box(0, 0, imageWidth, imageHeight)));
		}

		if (options.hasStaticRegion()) {
			Box box = staticRegion(imageWidth, imageHeight);
			if (usable(box)) {
				regions.add(RegionSource.configured(box));
			} else {
				dropped++;
			}
		}

		if (options.isUseDetections() && detections != null && !detections.isEmpty()) {
			List<RegionSource> boxes = new ArrayList<>();
			for (int seq = 0; seq < detections.size(); seq++) {
				dropped += readDetection(detections.get(seq), seq, imageWidth, imageHeight, boxes);
			}

			if (boxes.size() > options.getMaxRegions()) {
				// Keep the biggest boxes. A cap that dropped the subject of the photo would be
				// worse than no cap at all.
				boxes.sort(Comparator.comparingLong((RegionSource r) -> r.box().area()).reversed());
				int truncated = boxes.size() - options.getMaxRegions();
				regions.addAll(boxes.subList(0, options.getMaxRegions()));
				return new Resolution(List.copyOf(regions), dropped, truncated);
			}
			regions.addAll(boxes);
		}

		return new Resolution(List.copyOf(regions), dropped, 0);
	}

	/**
	 * Read one detection element.
	 *
	 * @return 1 when the element was dropped as unusable, 0 otherwise
	 */
	private int readDetection(String encoded, int seq, int imageWidth, int imageHeight, List<RegionSource> target) {
		JsonObject json;
		try {
			json = new JsonObject(encoded);
		} catch (Exception e) {
			log.warn("A 'detections' element was not JSON; ignoring it");
			return 1;
		}

		String coordinates = json.getString("coordinates");
		boolean normalized = DominantColorNodeOptions.NORMALIZED.equals(coordinates);
		if (!normalized && !DominantColorNodeOptions.ABSOLUTE_PIXELS.equals(coordinates)) {
			// Every producer on the wire today means absolute pixels; say so rather than guessing
			// silently.
			log.warn("A 'detections' element declared coordinates '{}'; assuming {}", coordinates,
				DominantColorNodeOptions.ABSOLUTE_PIXELS);
		}

		int payloadWidth = json.getInteger("imageWidth", 0);
		int payloadHeight = json.getInteger("imageHeight", 0);

		// Absolute boxes were measured against some image; if that image was not the one we just
		// decoded, they have to be rescaled. Silently mis-cropping every face is worse than a log
		// line. Normalised boxes are resolution independent by definition, so the payload
		// dimensions are irrelevant to them.
		double scaleX = 1d;
		double scaleY = 1d;
		if (!normalized && payloadWidth > 0 && payloadHeight > 0
			&& (payloadWidth != imageWidth || payloadHeight != imageHeight)) {
			scaleX = imageWidth / (double) payloadWidth;
			scaleY = imageHeight / (double) payloadHeight;
			log.info("A 'detections' element was measured against {}x{} but the decoded image is {}x{}; rescaling its box",
				payloadWidth, payloadHeight, imageWidth, imageHeight);
		}

		JsonObject bbox = json.getJsonObject("bbox");
		if (bbox == null) {
			return 1;
		}

		Integer frame = json.getInteger("frame");
		if (frame != null && frame != 0) {
			// This node only ever runs on stills, so a non-zero frame index can only mean a
			// video detector was wired into an image pipeline.
			return 1;
		}

		double x = bbox.getDouble("x", 0d);
		double y = bbox.getDouble("y", 0d);
		double w = bbox.getDouble("w", 0d);
		double h = bbox.getDouble("h", 0d);
		if (normalized) {
			x *= imageWidth;
			y *= imageHeight;
			w *= imageWidth;
			h *= imageHeight;
		} else {
			x *= scaleX;
			y *= scaleY;
			w *= scaleX;
			h *= scaleY;
		}

		Box box = Box.ofBounds(x, y, w, h).clampTo(imageWidth, imageHeight);
		if (!usable(box)) {
			return 1;
		}

		String type = json.getString("type", "object");
		String label = json.getString("label", type);
		target.add(new RegionSource(label + "-" + json.getInteger("index", seq), DETECTIONS_SOURCE,
			RegionKind.DETECTION, label, type, frame, json.getDouble("confidence"), box));
		return 0;
	}

	private Box staticRegion(int imageWidth, int imageHeight) {
		double x = options.getRegionX();
		double y = options.getRegionY();
		double w = options.getRegionW();
		double h = options.getRegionH();
		if (DominantColorNodeOptions.NORMALIZED.equals(options.getRegionCoordinates())) {
			x *= imageWidth;
			y *= imageHeight;
			w *= imageWidth;
			h *= imageHeight;
		}
		return Box.ofBounds(x, y, w, h).clampTo(imageWidth, imageHeight);
	}

	private boolean usable(Box box) {
		return box.area() >= options.getMinRegionPixels();
	}
}
