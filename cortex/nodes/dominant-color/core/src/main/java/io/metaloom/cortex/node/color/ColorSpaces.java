package io.metaloom.cortex.node.color;

/**
 * Conversions between sRGB, linear RGB, CIEXYZ and CIELAB, all against the D65 white point, plus
 * the HSL projection used for reporting.
 *
 * <p>
 * The transfer-function constants are kept in their exact rational form ({@code 216/24389} and
 * {@code 24389/27}) rather than the rounded {@code 0.008856} / {@code 7.787} that most references
 * print. The rational form makes {@code f} and its inverse exactly continuous at the join, which
 * matters here because every emitted k-means centroid round-trips Lab to RGB - a discontinuity
 * there produces off-by-one hex values on near-black colours.
 * </p>
 */
public final class ColorSpaces {

	/** D65 white point, X. */
	private static final double XN = 0.95047d;

	/** D65 white point, Y. */
	private static final double YN = 1.00000d;

	/** D65 white point, Z. */
	private static final double ZN = 1.08883d;

	/** CIE standard epsilon, exactly 216/24389. */
	private static final double EPS = 216.0d / 24389.0d;

	/** CIE standard kappa, exactly 24389/27. */
	private static final double KAPPA = 24389.0d / 27.0d;

	private ColorSpaces() {
	}

	/**
	 * Undo the sRGB transfer function.
	 *
	 * @param channel a channel value in [0, 1]
	 * @return the linear-light value in [0, 1]
	 */
	public static double srgbToLinear(double channel) {
		return channel <= 0.04045d ? channel / 12.92d : Math.pow((channel + 0.055d) / 1.055d, 2.4d);
	}

	/**
	 * Apply the sRGB transfer function.
	 *
	 * @param linear a linear-light value in [0, 1]
	 * @return the encoded channel value in [0, 1]
	 */
	public static double linearToSrgb(double linear) {
		return linear <= 0.0031308d ? 12.92d * linear : 1.055d * Math.pow(linear, 1.0d / 2.4d) - 0.055d;
	}

	/**
	 * @param rgb the sRGB colour
	 * @return the colour in CIELAB
	 */
	public static Lab rgbToLab(Rgb rgb) {
		return toLab(rgb.r(), rgb.g(), rgb.b());
	}

	/**
	 * Convert a packed ARGB int straight to CIELAB. This is the hot path - one call per sampled
	 * pixel - so it deliberately skips the intermediate {@link Rgb} allocation.
	 *
	 * @param argb packed value; any alpha byte is ignored
	 * @return the colour in CIELAB
	 */
	public static Lab packedToLab(int argb) {
		return toLab((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
	}

	private static Lab toLab(int r, int g, int b) {
		double rl = srgbToLinear(r / 255.0d);
		double gl = srgbToLinear(g / 255.0d);
		double bl = srgbToLinear(b / 255.0d);

		double x = 0.4124564d * rl + 0.3575761d * gl + 0.1804375d * bl;
		double y = 0.2126729d * rl + 0.7151522d * gl + 0.0721750d * bl;
		double z = 0.0193339d * rl + 0.1191920d * gl + 0.9503041d * bl;

		double fx = f(x / XN);
		double fy = f(y / YN);
		double fz = f(z / ZN);

		return new Lab(116.0d * fy - 16.0d, 500.0d * (fx - fy), 200.0d * (fy - fz));
	}

	/**
	 * Convert CIELAB back to sRGB.
	 *
	 * <p>
	 * The result is <strong>clamped</strong> into gamut. A k-means centroid is the arithmetic mean
	 * of in-gamut samples in Lab, and the sRGB gamut is not convex in Lab, so a centroid can land
	 * outside it. Clamping is the honest answer; the alternative - a gamut-mapping search - would
	 * change the reported colour by more than the clamp does.
	 * </p>
	 *
	 * @param lab the colour in CIELAB
	 * @return the nearest representable sRGB colour
	 */
	public static Rgb labToRgb(Lab lab) {
		double fy = (lab.l() + 16.0d) / 116.0d;
		double fx = fy + lab.a() / 500.0d;
		double fz = fy - lab.b() / 200.0d;

		double x = XN * finv(fx);
		double y = YN * (lab.l() > KAPPA * EPS ? Math.pow((lab.l() + 16.0d) / 116.0d, 3) : lab.l() / KAPPA);
		double z = ZN * finv(fz);

		double rl = 3.2404542d * x - 1.5371385d * y - 0.4985314d * z;
		double gl = -0.9692660d * x + 1.8760108d * y + 0.0415560d * z;
		double bl = 0.0556434d * x - 0.2040259d * y + 1.0572252d * z;

		return new Rgb(channel(rl), channel(gl), channel(bl));
	}

	/**
	 * @param rgb the sRGB colour
	 * @return the colour in HSL; hue is 0 for achromatic colours by CSS convention
	 */
	public static Hsl rgbToHsl(Rgb rgb) {
		double r = rgb.r() / 255.0d;
		double g = rgb.g() / 255.0d;
		double b = rgb.b() / 255.0d;

		double max = Math.max(r, Math.max(g, b));
		double min = Math.min(r, Math.min(g, b));
		double delta = max - min;
		double l = (max + min) / 2.0d;

		if (delta == 0) {
			return new Hsl(0, 0, l * 100.0d);
		}

		double s = delta / (1 - Math.abs(2 * l - 1));
		double h;
		if (max == r) {
			h = 60.0d * (((g - b) / delta) % 6.0d);
		} else if (max == g) {
			h = 60.0d * ((b - r) / delta + 2.0d);
		} else {
			h = 60.0d * ((r - g) / delta + 4.0d);
		}
		if (h < 0) {
			h += 360.0d;
		}
		return new Hsl(h, s * 100.0d, l * 100.0d);
	}

	private static double f(double t) {
		return t > EPS ? Math.cbrt(t) : (KAPPA * t + 16.0d) / 116.0d;
	}

	private static double finv(double t) {
		double cube = t * t * t;
		return cube > EPS ? cube : (116.0d * t - 16.0d) / KAPPA;
	}

	private static int channel(double linear) {
		double encoded = linearToSrgb(Math.max(0.0d, Math.min(1.0d, linear)));
		return (int) Math.round(Math.max(0.0d, Math.min(1.0d, encoded)) * 255.0d);
	}
}
