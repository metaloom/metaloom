package io.metaloom.cortex.node.depthmap;

/**
 * Which family of depth model the sidecar should run.
 *
 * <p>
 * The distinction matters more than accuracy does. Both modes return the same
 * {@code NEARNESS} convention on the wire (see {@link DepthmapNode}) - the difference
 * is whether the underlying numbers can be converted back to metres.
 * </p>
 */
public enum DepthMode {

	/**
	 * Affine-invariant relative depth (the default). The model predicts a disparity-like
	 * value defined up to an unknown scale and shift, which is enough to order objects
	 * front-to-back but says nothing about absolute distance.
	 */
	RELATIVE,

	/**
	 * Metric depth in metres. The response additionally carries {@code metric.min_m} /
	 * {@code metric.max_m} so the normalized map can be converted back. Heavier models,
	 * and single-view metric depth is meaningfully less reliable than relative - only
	 * reach for it when absolute distance is genuinely required.
	 */
	METRIC
}
