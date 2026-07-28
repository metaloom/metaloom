package io.metaloom.cortex.node.color;

/**
 * A colour in CIELAB (D65). This is the space the whole node works in: k-means clusters in it
 * because Euclidean distance there is roughly perceptual, and the namer measures against it
 * because CIEDE2000 is defined on it.
 *
 * @param l lightness L*, 0 (black) .. 100 (white)
 * @param a green-red axis a*, unbounded but practically about -128..127
 * @param b blue-yellow axis b*, unbounded but practically about -128..127
 */
public record Lab(double l, double a, double b) {

	/**
	 * Below this chroma the hue angle is meaningless and must not be reported.
	 *
	 * <p>
	 * Not simply {@code 0}: the D65 white point constants are not exactly consistent with the sRGB
	 * primary matrix, so a neutral grey converts to a residual chroma of about {@code 2e-5} rather
	 * than a clean zero. {@code 1e-4} sits above that numerical floor and four orders of magnitude
	 * below the roughly 1.0 that a human can perceive.
	 * </p>
	 */
	public static final double HUE_EPSILON = 1e-4;

	/**
	 * @return chroma C* of the LCh(ab) representation
	 */
	public double chroma() {
		return Math.hypot(a, b);
	}

	/**
	 * Hue angle h of the LCh(ab) representation.
	 *
	 * <p>
	 * Returns {@code null} rather than 0 for an achromatic colour. {@code atan2(0, 0)} is 0, and a
	 * caller that took that at face value would report pure grey as "hue 0", i.e. red.
	 * </p>
	 *
	 * @return the hue in degrees normalised to [0, 360), or null when the colour is achromatic
	 */
	public Double hue() {
		if (chroma() < HUE_EPSILON) {
			return null;
		}
		double degrees = Math.toDegrees(Math.atan2(b, a));
		return degrees < 0 ? degrees + 360 : degrees;
	}
}
