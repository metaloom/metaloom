package io.metaloom.cortex.node.color;

/**
 * Lightness band of a colour, derived from L*. Half of the modifier that
 * {@link ColorNamer} composes onto a basic colour term.
 */
public enum Lightness {

	VERY_DARK,
	DARK,
	MEDIUM,
	LIGHT,
	VERY_LIGHT;

	/**
	 * @param l lightness L*, 0..100
	 * @return the band it falls in
	 */
	public static Lightness of(double l) {
		if (l < 20) {
			return VERY_DARK;
		}
		if (l < 40) {
			return DARK;
		}
		if (l < 65) {
			return MEDIUM;
		}
		if (l < 85) {
			return LIGHT;
		}
		return VERY_LIGHT;
	}
}
