package io.metaloom.cortex.node.color;

/**
 * A resolved region to measure, with enough provenance for a consumer to trace it back to whatever
 * produced it.
 *
 * @param id         stable identifier within one result, e.g. {@code whole}, {@code region} or
 *                   {@code face-0}
 * @param source     what produced it: {@code image}, {@code config}, or the upstream node id
 * @param kind       the region kind
 * @param label      the detector's label, or null for non-detection regions
 * @param type       the detector's type, or null for non-detection regions
 * @param frame      the detector's frame index, or null
 * @param confidence the detector's confidence, or null
 * @param box        the region in image pixels, already clamped to the image bounds
 */
public record RegionSource(String id, String source, RegionKind kind, String label, String type,
	Integer frame, Double confidence, Box box) {

	/**
	 * @param box the full-frame box
	 * @return the whole-image region
	 */
	public static RegionSource wholeImage(Box box) {
		return new RegionSource("whole", "image", RegionKind.IMAGE, null, null, null, null, box);
	}

	/**
	 * @param box the configured box, resolved to pixels
	 * @return the statically configured region
	 */
	public static RegionSource configured(Box box) {
		return new RegionSource("region", "config", RegionKind.CONFIG, null, null, null, null, box);
	}
}
