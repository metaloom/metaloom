package io.metaloom.cortex.node.scenelayout;

/**
 * The depth distribution over one object's sampling region, in NEARNESS units where 1.0 is
 * nearest to the camera.
 *
 * <p>
 * A distribution rather than a single value because one pixel is not an object. Sampling the
 * centre alone lands on a hand, a hat, or a hole; the quartiles both give a robust centre and
 * say how depth-consistent the object is.
 * </p>
 *
 * @param near   the median nearness - the object's depth
 * @param p25    lower quartile
 * @param p75    upper quartile
 * @param pixels how many pixels the statistic was computed from
 */
public record DepthStats(double near, double p25, double p75, int pixels) {

	/**
	 * Interquartile spread. High spread means a slanted object, a loose box, or an unreliable
	 * depth region - and relations involving it deserve proportionally less trust, which is
	 * exactly how {@link RelationSolver} scores them.
	 *
	 * @return p75 - p25
	 */
	public double spread() {
		return Math.max(0, p75 - p25);
	}
}
