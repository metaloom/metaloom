package io.metaloom.cortex.node.color;

/**
 * Colour difference metrics.
 *
 * <p>
 * Two are needed, and which one goes where is a correctness question, not a taste question:
 * </p>
 *
 * <ul>
 * <li><strong>{@link #squaredEuclidean} (dE76 squared) drives k-means.</strong> Lloyd's algorithm
 * only terminates because each step provably lowers the within-cluster sum of squared distances,
 * and that proof needs the arithmetic mean to be the distance's centroid. Squared Euclidean is the
 * one metric here for which that holds.</li>
 * <li><strong>{@link #ciede2000} drives naming only.</strong> CIEDE2000 is not a metric - it
 * violates the triangle inequality and is not a Bregman divergence - so the mean is not its
 * centroid and Lloyd's loop under it has no monotone-decrease guarantee and can oscillate forever.
 * It is however markedly more accurate, and naming needs exactly one distance evaluation per
 * emitted colour per prototype, where that accuracy is free.</li>
 * </ul>
 */
public final class ColorDistance {

	/** 25^7, the constant in the CIEDE2000 chroma-weighting term. */
	private static final double POW25_7 = 6103515625.0d;

	private ColorDistance() {
	}

	/**
	 * CIEDE2000 colour difference with {@code kL = kC = kH = 1}.
	 *
	 * <p>
	 * Implemented from Sharma, Wu and Dalal (2005). The two zero-chroma guards below are not
	 * optional: without them a grey-versus-grey comparison divides 0 by 0 and returns
	 * {@code NaN}, and a grey image is the single most common degenerate input to this node.
	 * </p>
	 *
	 * @param p first colour
	 * @param q second colour
	 * @return the difference; 0 for identical colours
	 */
	public static double ciede2000(Lab p, Lab q) {
		double l1 = p.l();
		double a1 = p.a();
		double b1 = p.b();
		double l2 = q.l();
		double a2 = q.a();
		double b2 = q.b();

		double c1 = Math.hypot(a1, b1);
		double c2 = Math.hypot(a2, b2);
		double cBar = (c1 + c2) / 2.0d;
		double cBar7 = Math.pow(cBar, 7);
		// Guard 1: cBar == 0 would make cBar7 / (cBar7 + POW25_7) evaluate 0/0.
		double g = cBar == 0 ? 0.0d : 0.5d * (1 - Math.sqrt(cBar7 / (cBar7 + POW25_7)));

		double a1p = (1 + g) * a1;
		double a2p = (1 + g) * a2;
		double c1p = Math.hypot(a1p, b1);
		double c2p = Math.hypot(a2p, b2);
		double h1p = hueDegrees(a1p, b1);
		double h2p = hueDegrees(a2p, b2);

		double dLp = l2 - l1;
		double dCp = c2p - c1p;

		double dhp;
		if (c1p * c2p == 0) {
			dhp = 0;
		} else if (Math.abs(h2p - h1p) <= 180) {
			dhp = h2p - h1p;
		} else if (h2p - h1p > 180) {
			dhp = h2p - h1p - 360;
		} else {
			dhp = h2p - h1p + 360;
		}
		double dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp) / 2.0d);

		double lBarP = (l1 + l2) / 2.0d;
		double cBarP = (c1p + c2p) / 2.0d;

		double hBarP;
		if (c1p * c2p == 0) {
			hBarP = h1p + h2p;
		} else if (Math.abs(h1p - h2p) <= 180) {
			hBarP = (h1p + h2p) / 2.0d;
		} else if (h1p + h2p < 360) {
			hBarP = (h1p + h2p + 360) / 2.0d;
		} else {
			hBarP = (h1p + h2p - 360) / 2.0d;
		}

		double t = 1
			- 0.17d * cosDegrees(hBarP - 30)
			+ 0.24d * cosDegrees(2 * hBarP)
			+ 0.32d * cosDegrees(3 * hBarP + 6)
			- 0.20d * cosDegrees(4 * hBarP - 63);

		double dTheta = 30 * Math.exp(-Math.pow((hBarP - 275) / 25.0d, 2));
		double cBarP7 = Math.pow(cBarP, 7);
		// Guard 2: same 0/0 as guard 1, on the rotation term.
		double rc = cBarP == 0 ? 0.0d : 2 * Math.sqrt(cBarP7 / (cBarP7 + POW25_7));

		double sl = 1 + (0.015d * Math.pow(lBarP - 50, 2)) / Math.sqrt(20 + Math.pow(lBarP - 50, 2));
		double sc = 1 + 0.045d * cBarP;
		double sh = 1 + 0.015d * cBarP * t;
		double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;

		double tl = dLp / sl;
		double tc = dCp / sc;
		double th = dHp / sh;
		return Math.sqrt(tl * tl + tc * tc + th * th + rt * tc * th);
	}

	/**
	 * Squared Euclidean distance in CIELAB, i.e. dE76 without the square root. The root is
	 * omitted because k-means only ever compares distances, and skipping it removes one
	 * {@code sqrt} per point per centroid per iteration.
	 *
	 * @param l1 first lightness
	 * @param a1 first a*
	 * @param b1 first b*
	 * @param l2 second lightness
	 * @param a2 second a*
	 * @param b2 second b*
	 * @return the squared distance
	 */
	public static double squaredEuclidean(double l1, double a1, double b1, double l2, double a2, double b2) {
		double dl = l1 - l2;
		double da = a1 - a2;
		double db = b1 - b2;
		return dl * dl + da * da + db * db;
	}

	private static double hueDegrees(double a, double b) {
		if (a == 0 && b == 0) {
			return 0;
		}
		double degrees = Math.toDegrees(Math.atan2(b, a));
		return degrees < 0 ? degrees + 360 : degrees;
	}

	private static double cosDegrees(double degrees) {
		return Math.cos(Math.toRadians(degrees));
	}
}
