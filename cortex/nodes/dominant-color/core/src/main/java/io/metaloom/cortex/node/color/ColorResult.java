package io.metaloom.cortex.node.color;

import java.util.List;

/**
 * The measured palette of one region.
 *
 * @param region    where the pixels came from
 * @param pixels    how many sampled, non-transparent pixels were clustered. This is the
 *                  denominator of every {@link ColorEntry#share()} and is emitted alongside them,
 *                  because a share without its denominator is not interpretable
 * @param converged whether k-means settled within its iteration budget
 * @param palette   the colours, ranked by share descending; never empty
 */
public record ColorResult(RegionSource region, int pixels, boolean converged, List<ColorEntry> palette) {

	/**
	 * One colour of a region's palette.
	 *
	 * @param share the fraction of sampled pixels this colour accounts for
	 * @param rgb   the colour in sRGB
	 * @param lab   the colour in CIELAB - the cluster centroid, before the round trip to sRGB
	 * @param hsl   the colour in HSL
	 * @param name  the human-readable name
	 */
	public record ColorEntry(double share, Rgb rgb, Lab lab, Hsl hsl, ColorName name) {
	}

	/**
	 * @return the most prevalent colour
	 */
	public ColorEntry dominant() {
		return palette.get(0);
	}
}
