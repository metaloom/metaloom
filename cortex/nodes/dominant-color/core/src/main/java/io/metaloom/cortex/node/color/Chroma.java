package io.metaloom.cortex.node.color;

/**
 * Saturation band of a colour, derived from C*. The other half of the modifier that
 * {@link ColorNamer} composes onto a basic colour term.
 *
 * <p>
 * {@link #ACHROMATIC} is not a band on the same scale as the others - it is the marker that the
 * colour never reached the hue-based naming path at all. It exists so that a consumer, and a test,
 * can assert that no chroma modifier was composed onto a grey.
 * </p>
 */
public enum Chroma {

	ACHROMATIC,
	GREYISH,
	MUTED,
	STRONG,
	VIVID;

	/**
	 * @param c chroma C*
	 * @param achromaticThreshold below this the colour is achromatic
	 * @return the band it falls in
	 */
	public static Chroma of(double c, double achromaticThreshold) {
		if (c < achromaticThreshold) {
			return ACHROMATIC;
		}
		if (c < 25) {
			return GREYISH;
		}
		if (c < 45) {
			return MUTED;
		}
		if (c < 70) {
			return STRONG;
		}
		return VIVID;
	}
}
