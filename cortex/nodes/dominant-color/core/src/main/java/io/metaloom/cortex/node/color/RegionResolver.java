package io.metaloom.cortex.node.color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
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

	private final DominantColorNodeOptions options;

	public RegionResolver(DominantColorNodeOptions options) {
		this.options = options;
	}

	/**
	 * @param upstreamOutputs the pipeline's upstream output map, keyed by node id
	 * @param imageWidth      the decoded image width
	 * @param imageHeight     the decoded image height
	 * @return the resolved regions
	 */
	public Resolution resolve(Map<String, Map<String, Object>> upstreamOutputs, int imageWidth, int imageHeight) {
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

		if (options.isUseDetections() && upstreamOutputs != null) {
			List<RegionSource> detections = new ArrayList<>();
			boolean prefixIds = options.getDetectionSources().size() > 1;
			for (String nodeId : options.getDetectionSources()) {
				Map<String, Object> outputs = upstreamOutputs.get(nodeId);
				Object payload = outputs == null ? null : outputs.get("detections");
				if (payload == null) {
					continue;
				}
				dropped += readDetections(nodeId, payload, imageWidth, imageHeight, prefixIds, detections);
			}

			if (detections.size() > options.getMaxRegions()) {
				// Keep the biggest boxes. A cap that dropped the subject of the photo would be
				// worse than no cap at all.
				detections.sort(Comparator.comparingLong((RegionSource r) -> r.box().area()).reversed());
				int truncated = detections.size() - options.getMaxRegions();
				regions.addAll(detections.subList(0, options.getMaxRegions()));
				return new Resolution(List.copyOf(regions), dropped, truncated);
			}
			regions.addAll(detections);
		}

		return new Resolution(List.copyOf(regions), dropped, 0);
	}

	/**
	 * @return how many detections in this payload were dropped
	 */
	private int readDetections(String nodeId, Object payload, int imageWidth, int imageHeight,
		boolean prefixIds, List<RegionSource> target) {
		JsonObject json;
		try {
			json = new JsonObject(payload.toString());
		} catch (Exception e) {
			log.warn("Upstream node '{}' emitted a 'detections' output that is not JSON; ignoring it", nodeId);
			return 0;
		}

		String coordinates = json.getString("coordinates");
		boolean normalized = DominantColorNodeOptions.NORMALIZED.equals(coordinates);
		if (!normalized && !DominantColorNodeOptions.ABSOLUTE_PIXELS.equals(coordinates)) {
			// Every producer on the wire today means absolute pixels; say so rather than guessing
			// silently.
			log.warn("Upstream node '{}' declared coordinates '{}'; assuming {}", nodeId, coordinates,
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
			log.info("Upstream node '{}' measured against {}x{} but the decoded image is {}x{}; rescaling its boxes",
				nodeId, payloadWidth, payloadHeight, imageWidth, imageHeight);
		}

		JsonArray items = json.getJsonArray("detections", new JsonArray());
		int dropped = 0;
		for (int i = 0; i < items.size(); i++) {
			JsonObject item = items.getJsonObject(i);
			if (item == null) {
				dropped++;
				continue;
			}
			JsonObject bbox = item.getJsonObject("bbox");
			if (bbox == null) {
				dropped++;
				continue;
			}

			Integer frame = item.getInteger("frame");
			if (frame != null && frame != 0) {
				// This node only ever runs on stills, so a non-zero frame index can only mean a
				// video detector was wired into an image pipeline.
				dropped++;
				continue;
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
				dropped++;
				continue;
			}

			int index = item.getInteger("index", i);
			String type = item.getString("type", "object");
			String label = item.getString("label", type);
			String id = label + "-" + index;
			if (prefixIds) {
				// Two detectors can both call their first box "face-0".
				id = nodeId + ":" + id;
			}
			target.add(new RegionSource(id, nodeId, RegionKind.DETECTION, label, type, frame,
				item.getDouble("confidence"), box));
		}
		return dropped;
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
