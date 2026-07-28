package io.metaloom.cortex.node.scenelayout;

import java.util.Arrays;

/**
 * Turns a bounding box plus a depth map into one object's depth distribution.
 *
 * <p>
 * Pure arithmetic - no I/O, no node, no Loom - so the part of this feature most likely to be
 * wrong is also the part that is trivial to test.
 * </p>
 *
 * <h2>Why the core inset matters</h2>
 *
 * <p>
 * A bounding box is a rectangle drawn around a thing that is not rectangular, so its corners are
 * background. Averaging the whole box pulls a foreground person's depth toward the wall behind
 * them, and for two people at similar distance that is enough to flip their ordering. Sampling
 * only the central core keeps the statistic on the object.
 * </p>
 */
public final class DepthSampler {

	private DepthSampler() {
	}

	/**
	 * Sample the depth distribution over a box's central core.
	 *
	 * @param map           the depth map
	 * @param mapBox        the box, already projected into map coordinates
	 * @param coreInset     fraction to inset on each side before sampling, e.g. 0.25 for the central 50%
	 * @param minCorePixels reject the object when its core covers fewer pixels than this - the
	 *                      statistic would be noise
	 * @return the distribution, or null when the core is too small to measure
	 */
	public static DepthStats sample(DepthMap map, BoxF mapBox, double coreInset, int minCorePixels) {
		float[] samples = map.samplesIn(mapBox.core(coreInset));
		if (samples.length < Math.max(1, minCorePixels)) {
			return null;
		}
		Arrays.sort(samples);
		return new DepthStats(
			quantile(samples, 0.50),
			quantile(samples, 0.25),
			quantile(samples, 0.75),
			samples.length);
	}

	/**
	 * Quantile of an already-sorted array, by nearest rank.
	 *
	 * @param sorted ascending samples, non-empty
	 * @param q      the quantile in [0, 1]
	 * @return the value at that quantile
	 */
	static double quantile(float[] sorted, double q) {
		int idx = (int) Math.round(Math.max(0, Math.min(1, q)) * (sorted.length - 1));
		return sorted[idx];
	}
}
