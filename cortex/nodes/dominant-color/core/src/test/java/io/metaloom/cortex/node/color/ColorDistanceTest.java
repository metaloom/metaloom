package io.metaloom.cortex.node.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Pins CIEDE2000 against the reference dataset published with the formula (Sharma, Wu and Dalal,
 * <i>The CIEDE2000 Color-Difference Formula</i>, 2005, Table 1).
 *
 * <p>
 * This is not ceremony. Roughly half of published CIEDE2000 implementations get the hue-averaging
 * or the {@code Rt} rotation branch wrong, and the error is invisible on ordinary colours - the
 * dataset exists precisely because it is built from the pairs that straddle those branches.
 * </p>
 */
public class ColorDistanceTest {

	/** {L1, a1, b1, L2, a2, b2, expected dE00} - all 34 reference pairs. */
	private static final double[][] SHARMA = {
		{ 50.0000, 2.6772, -79.7751, 50.0000, 0.0000, -82.7485, 2.0425 },
		{ 50.0000, 3.1571, -77.2803, 50.0000, 0.0000, -82.7485, 2.8615 },
		{ 50.0000, 2.8361, -74.0200, 50.0000, 0.0000, -82.7485, 3.4412 },
		{ 50.0000, -1.3802, -84.2814, 50.0000, 0.0000, -82.7485, 1.0000 },
		{ 50.0000, -1.1848, -84.8006, 50.0000, 0.0000, -82.7485, 1.0000 },
		{ 50.0000, -0.9009, -85.5211, 50.0000, 0.0000, -82.7485, 1.0000 },
		{ 50.0000, 0.0000, 0.0000, 50.0000, -1.0000, 2.0000, 2.3669 },
		{ 50.0000, -1.0000, 2.0000, 50.0000, 0.0000, 0.0000, 2.3669 },
		{ 50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0009, 7.1792 },
		{ 50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0010, 7.1792 },
		{ 50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0011, 7.2195 },
		{ 50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0012, 7.2195 },
		{ 50.0000, -0.0010, 2.4900, 50.0000, 0.0009, -2.4900, 4.8045 },
		{ 50.0000, -0.0010, 2.4900, 50.0000, 0.0010, -2.4900, 4.8045 },
		{ 50.0000, -0.0010, 2.4900, 50.0000, 0.0011, -2.4900, 4.7461 },
		{ 50.0000, 2.5000, 0.0000, 50.0000, 0.0000, -2.5000, 4.3065 },
		{ 50.0000, 2.5000, 0.0000, 73.0000, 25.0000, -18.0000, 27.1492 },
		{ 50.0000, 2.5000, 0.0000, 61.0000, -5.0000, 29.0000, 22.8977 },
		{ 50.0000, 2.5000, 0.0000, 56.0000, -27.0000, -3.0000, 31.9030 },
		{ 50.0000, 2.5000, 0.0000, 58.0000, 24.0000, 15.0000, 19.4535 },
		{ 50.0000, 2.5000, 0.0000, 50.0000, 3.1736, 0.5854, 1.0000 },
		{ 50.0000, 2.5000, 0.0000, 50.0000, 3.2972, 0.0000, 1.0000 },
		{ 50.0000, 2.5000, 0.0000, 50.0000, 1.8634, 0.5757, 1.0000 },
		{ 50.0000, 2.5000, 0.0000, 50.0000, 3.2592, 0.3350, 1.0000 },
		{ 60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644 },
		{ 63.0109, -31.0961, -5.8663, 62.8187, -29.7946, -4.0864, 1.2630 },
		{ 61.2901, 3.7196, -5.3901, 61.4292, 2.2480, -4.9620, 1.8731 },
		{ 35.0831, -44.1164, 3.7933, 35.0232, -40.0716, 1.5901, 1.8645 },
		{ 22.7233, 20.0904, -46.6940, 23.0331, 14.9730, -42.5619, 2.0373 },
		{ 36.4612, 47.8580, 18.3852, 36.2715, 50.5065, 21.2231, 1.4146 },
		{ 90.8027, -2.0831, 1.4410, 91.1528, -1.6435, 0.0447, 1.4441 },
		{ 90.9257, -0.5406, -0.9208, 88.6381, -0.8985, -0.7239, 1.5381 },
		{ 6.7747, -0.2908, -2.4247, 5.8714, -0.0985, -2.2286, 0.6377 },
		{ 2.0776, 0.0795, -1.1350, 0.9033, -0.0636, -0.5514, 0.9082 }
	};

	@Test
	public void testMatchesTheSharmaReferenceDataset() {
		for (int i = 0; i < SHARMA.length; i++) {
			double[] row = SHARMA[i];
			Lab p = new Lab(row[0], row[1], row[2]);
			Lab q = new Lab(row[3], row[4], row[5]);
			assertThat(ColorDistance.ciede2000(p, q))
				.as("Sharma pair " + (i + 1))
				.isCloseTo(row[6], within(1e-4));
		}
	}

	/**
	 * Two identical greys have zero chroma on both sides, which is the input that divides 0 by 0
	 * in the two weighting terms. A grey image is the most common degenerate input to this node,
	 * so this must be 0.0 and never NaN.
	 */
	@Test
	public void testGreyAgainstGreyIsZeroNotNaN() {
		Lab grey = ColorSpaces.rgbToLab(new Rgb(128, 128, 128));
		double distance = ColorDistance.ciede2000(grey, grey);
		assertThat(Double.isNaN(distance)).as("distance is NaN").isFalse();
		assertThat(distance).isCloseTo(0d, within(1e-12));

		Lab black = ColorSpaces.rgbToLab(new Rgb(0, 0, 0));
		assertThat(Double.isNaN(ColorDistance.ciede2000(black, black))).isFalse();
		assertThat(Double.isNaN(ColorDistance.ciede2000(black, grey))).isFalse();
	}

	@Test
	public void testIsSymmetric() {
		for (double[] row : SHARMA) {
			Lab p = new Lab(row[0], row[1], row[2]);
			Lab q = new Lab(row[3], row[4], row[5]);
			assertThat(ColorDistance.ciede2000(p, q)).isCloseTo(ColorDistance.ciede2000(q, p), within(1e-9));
		}
	}

	@Test
	public void testIdenticalColorsAreZero() {
		Lab lab = ColorSpaces.rgbToLab(Rgb.ofHex("#3B6EA5"));
		assertThat(ColorDistance.ciede2000(lab, lab)).isCloseTo(0d, within(1e-12));
	}

	@Test
	public void testSquaredEuclideanMatchesAHandComputedValue() {
		// (3-0)^2 + (4-0)^2 + (12-0)^2 = 9 + 16 + 144 = 169
		assertThat(ColorDistance.squaredEuclidean(3, 4, 12, 0, 0, 0)).isCloseTo(169d, within(1e-12));
		assertThat(ColorDistance.squaredEuclidean(1, 2, 3, 1, 2, 3)).isCloseTo(0d, within(1e-12));
	}
}
