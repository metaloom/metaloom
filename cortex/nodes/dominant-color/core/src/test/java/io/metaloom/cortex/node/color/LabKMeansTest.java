package io.metaloom.cortex.node.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.color.LabKMeans.Cluster;
import io.metaloom.cortex.node.color.LabKMeans.Result;

public class LabKMeansTest {

	private static final Lab RED = ColorSpaces.rgbToLab(Rgb.ofHex("#FF0000"));

	private static final Lab GREEN = ColorSpaces.rgbToLab(Rgb.ofHex("#00FF00"));

	private static final Lab BLUE = ColorSpaces.rgbToLab(Rgb.ofHex("#0000FF"));

	@Test
	public void testThreeWellSeparatedClustersAreRecoveredExactly() {
		Result result = LabKMeans.cluster(points(100, RED, 100, GREEN, 100, BLUE), 3, 30, 0.5d, 42L);

		assertThat(result.clusters()).hasSize(3);
		assertThat(result.usablePixels()).isEqualTo(300);
		assertThat(result.converged()).isTrue();
		for (Cluster cluster : result.clusters()) {
			assertThat(cluster.share()).isCloseTo(1 / 3d, within(1e-9));
			assertThat(cluster.count()).isEqualTo(100);
		}
		assertThat(result.clusters().stream().map(c -> nearestName(c.center())).toList())
			.containsExactlyInAnyOrder("red", "green", "blue");
	}

	@Test
	public void testSkewedClustersRankByShare() {
		Result result = LabKMeans.cluster(points(700, RED, 200, GREEN, 100, BLUE), 3, 30, 0.5d, 42L);

		assertThat(result.clusters()).hasSize(3);
		assertThat(result.clusters().get(0).share()).isCloseTo(0.7d, within(1e-9));
		assertThat(result.clusters().get(1).share()).isCloseTo(0.2d, within(1e-9));
		assertThat(result.clusters().get(2).share()).isCloseTo(0.1d, within(1e-9));
		assertCenter(result.clusters().get(0).center(), RED);
		assertCenter(result.clusters().get(1).center(), GREEN);
		assertCenter(result.clusters().get(2).center(), BLUE);
	}

	@Test
	public void testTheSameInputAndSeedProducesTheIdenticalResult() {
		double[] input = points(431, RED, 217, GREEN, 93, BLUE, 61, ColorSpaces.rgbToLab(Rgb.ofHex("#3B6EA5")));
		Result first = LabKMeans.cluster(input, 4, 30, 0.5d, 42L);
		Result second = LabKMeans.cluster(input, 4, 30, 0.5d, 42L);
		assertThat(second).isEqualTo(first);
	}

	/**
	 * With well-separated clusters the seed must not be load-bearing for correctness - it only
	 * decides how fast Lloyd gets there.
	 */
	@Test
	public void testADifferentSeedFindsTheSamePartition() {
		double[] input = points(100, RED, 100, GREEN, 100, BLUE);
		Result seven = LabKMeans.cluster(input, 3, 30, 0.5d, 7L);
		Result fortyTwo = LabKMeans.cluster(input, 3, 30, 0.5d, 42L);
		assertThat(seven.clusters().stream().map(c -> nearestName(c.center())).toList())
			.containsExactlyInAnyOrderElementsOf(fortyTwo.clusters().stream().map(c -> nearestName(c.center())).toList());
	}

	@Test
	public void testMoreClustersThanPointsIsReducedToThePointCount() {
		Result result = LabKMeans.cluster(points(1, RED, 1, BLUE), 5, 30, 0.5d, 42L);
		assertThat(result.clusters()).hasSize(2);
		assertThat(result.usablePixels()).isEqualTo(2);
	}

	@Test
	public void testIdenticalPointsCollapseToOneCluster() {
		Result result = LabKMeans.cluster(points(1000, RED), 5, 30, 0.5d, 42L);
		assertThat(result.clusters()).hasSize(1);
		assertThat(result.clusters().get(0).share()).isCloseTo(1.0d, within(1e-12));
		assertCenter(result.clusters().get(0).center(), RED);
	}

	@Test
	public void testEmptyInputReturnsAnEmptyResultRatherThanThrowing() {
		Result result = LabKMeans.cluster(new double[0], 5, 30, 0.5d, 42L);
		assertThat(result.isEmpty()).isTrue();
		assertThat(result.usablePixels()).isZero();
		assertThat(result.iterations()).isZero();
		assertThat(result.converged()).isTrue();
	}

	@Test
	public void testAnExhaustedIterationBudgetStillYieldsAValidResult() {
		double[] input = spread(2000, 4242L);
		Result result = LabKMeans.cluster(input, 8, 1, 1e-9d, 42L);
		assertThat(result.converged()).isFalse();
		assertThat(result.iterations()).isEqualTo(1);
		assertThat(result.clusters()).isNotEmpty();
		assertThat(result.clusters().stream().mapToInt(Cluster::count).sum()).isEqualTo(2000);
	}

	@Test
	public void testSharesSumToOne() {
		Result result = LabKMeans.cluster(spread(5000, 99L), 6, 30, 0.5d, 42L);
		assertThat(result.clusters().stream().mapToDouble(Cluster::share).sum()).isCloseTo(1.0d, within(1e-9));
	}

	/**
	 * A centroid is a sum divided by a count, so even a cluster of identical points comes back a
	 * few ULPs away from the input. Compare within a tolerance far below a perceptible difference
	 * rather than by record equality - bit-for-bit reproducibility is pinned separately, by
	 * {@link #testTheSameInputAndSeedProducesTheIdenticalResult()}.
	 */
	private static void assertCenter(Lab actual, Lab expected) {
		assertThat(actual.l()).as("L*").isCloseTo(expected.l(), within(1e-6));
		assertThat(actual.a()).as("a*").isCloseTo(expected.a(), within(1e-6));
		assertThat(actual.b()).as("b*").isCloseTo(expected.b(), within(1e-6));
	}

	private static String nearestName(Lab lab) {
		return ColorNamer.defaults().name(lab).term();
	}

	/** Build a flat point array from alternating (count, colour) pairs. */
	private static double[] points(Object... spec) {
		int total = 0;
		for (int i = 0; i < spec.length; i += 2) {
			total += (Integer) spec[i];
		}
		double[] out = new double[total * 3];
		int p = 0;
		for (int i = 0; i < spec.length; i += 2) {
			int count = (Integer) spec[i];
			Lab lab = (Lab) spec[i + 1];
			for (int j = 0; j < count; j++) {
				out[p++] = lab.l();
				out[p++] = lab.a();
				out[p++] = lab.b();
			}
		}
		return out;
	}

	/** A reproducible cloud of points with no natural cluster structure. */
	private static double[] spread(int count, long seed) {
		java.util.Random random = new java.util.Random(seed);
		double[] out = new double[count * 3];
		for (int i = 0; i < count; i++) {
			out[i * 3] = random.nextDouble() * 100;
			out[i * 3 + 1] = random.nextDouble() * 200 - 100;
			out[i * 3 + 2] = random.nextDouble() * 200 - 100;
		}
		return out;
	}
}
