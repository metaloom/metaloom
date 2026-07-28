package io.metaloom.cortex.node.scenelayout;

/**
 * One detected object, positioned in the image and in depth.
 *
 * @param id     stable identifier within this result, e.g. {@code "face-0"}; used as the
 *               subject/object of every relation
 * @param label  human-readable class, e.g. {@code "face"} - for a future object detector this
 *               is the class name
 * @param type   the detection's {@code type} field, e.g. {@code "face"}
 * @param source the node id that produced the box, e.g. {@code "facedetect"}, or {@code "loom"}
 *               when it was read back over REST
 * @param box    the bounding box in <em>image</em> coordinates (what callers and the payload see)
 * @param mapBox the same box projected into <em>depth-map</em> coordinates (what sampling uses)
 * @param confidence the detector's own confidence
 * @param depth  the sampled depth distribution, or null when the object could not be sampled
 * @param band   which depth band it falls in, or null before banding has run
 */
public record LayoutObject(
	String id,
	String label,
	String type,
	String source,
	BoxF box,
	BoxF mapBox,
	double confidence,
	DepthStats depth,
	DepthBand band) {

	/**
	 * Return a copy carrying the given depth statistics and band.
	 *
	 * @param depth the sampled distribution
	 * @param band  the assigned band
	 * @return the enriched object
	 */
	public LayoutObject with(DepthStats depth, DepthBand band) {
		return new LayoutObject(id, label, type, source, box, mapBox, confidence, depth, band);
	}
}
