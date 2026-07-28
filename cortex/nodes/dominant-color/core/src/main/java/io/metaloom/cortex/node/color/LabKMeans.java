package io.metaloom.cortex.node.color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Deterministic k-means over points in CIELAB.
 *
 * <h2>Why squared Euclidean and not CIEDE2000</h2>
 *
 * Lloyd's algorithm terminates only because every step provably lowers the within-cluster sum of
 * squared distances, and that proof requires the arithmetic mean to be the distance's centroid.
 * That holds for squared Euclidean and does not hold for CIEDE2000, which is not a metric - it
 * violates the triangle inequality and is not a Bregman divergence. Clustering under it would have
 * no monotone-decrease guarantee and could oscillate forever. CIEDE2000 earns its accuracy in
 * {@link ColorNamer}, where it runs once per emitted colour rather than once per pixel per
 * iteration.
 *
 * <h2>Why the result is reproducible</h2>
 *
 * Two independent facts are needed and both are load-bearing: {@link Random} is specified
 * bit-for-bit by the JLS, <em>and</em> the caller supplies its points in a fixed order (stride
 * sampling in {@link PixelSampler}). A fixed seed on its own would not be enough - k-means++ walks
 * the point array, so a reordered array selects different seeds. Empty-cluster re-seeding is
 * likewise deterministic rather than random, for the same reason.
 */
public final class LabKMeans {

	/**
	 * One cluster of the result.
	 *
	 * @param center the centroid in CIELAB
	 * @param count  how many sampled points it holds
	 * @param share  {@code count / usablePixels}, in [0, 1]
	 */
	public record Cluster(Lab center, int count, double share) {
	}

	/**
	 * @param clusters     the clusters, ranked by share descending
	 * @param usablePixels how many points were clustered - the denominator of every share
	 * @param iterations   how many Lloyd iterations ran
	 * @param converged    whether the centroids settled within the iteration budget
	 */
	public record Result(List<Cluster> clusters, int usablePixels, int iterations, boolean converged) {

		/**
		 * @return true when there was nothing to cluster
		 */
		public boolean isEmpty() {
			return clusters.isEmpty();
		}
	}

	private LabKMeans() {
	}

	/**
	 * Cluster a flat array of CIELAB points.
	 *
	 * @param lab           points as {@code [l0, a0, b0, l1, a1, b1, ...]}; length must be a
	 *                      multiple of 3
	 * @param k             requested cluster count. Silently reduced to the number of points when
	 *                      there are fewer - a caller that also wants to guard against fewer
	 *                      <em>distinct</em> points than k should reduce k before calling
	 * @param maxIterations Lloyd iteration budget
	 * @param epsilon       convergence threshold on the largest centroid shift, in Lab units
	 * @param seed          k-means++ seed
	 * @return the ranked clusters, or an empty result when there are no points
	 */
	public static Result cluster(double[] lab, int k, int maxIterations, double epsilon, long seed) {
		int n = lab.length / 3;
		if (n == 0 || k < 1) {
			return new Result(List.of(), 0, 0, true);
		}
		int clusterCount = Math.min(k, n);

		double[] centers = seedPlusPlus(lab, n, clusterCount, seed);
		int[] assignment = new int[n];
		int[] counts = new int[clusterCount];
		double[] sums = new double[clusterCount * 3];

		int iteration = 0;
		boolean converged = false;
		while (iteration < maxIterations) {
			iteration++;
			assign(lab, n, centers, clusterCount, assignment);
			reseedEmptyClusters(lab, n, centers, clusterCount, assignment);

			java.util.Arrays.fill(counts, 0);
			java.util.Arrays.fill(sums, 0d);
			for (int i = 0; i < n; i++) {
				int c = assignment[i];
				counts[c]++;
				sums[c * 3] += lab[i * 3];
				sums[c * 3 + 1] += lab[i * 3 + 1];
				sums[c * 3 + 2] += lab[i * 3 + 2];
			}

			double shift = 0;
			for (int c = 0; c < clusterCount; c++) {
				if (counts[c] == 0) {
					continue;
				}
				double nl = sums[c * 3] / counts[c];
				double na = sums[c * 3 + 1] / counts[c];
				double nb = sums[c * 3 + 2] / counts[c];
				shift = Math.max(shift, Math.sqrt(ColorDistance.squaredEuclidean(
					centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2], nl, na, nb)));
				centers[c * 3] = nl;
				centers[c * 3 + 1] = na;
				centers[c * 3 + 2] = nb;
			}

			if (shift < epsilon) {
				converged = true;
				break;
			}
		}

		List<Cluster> clusters = new ArrayList<>(clusterCount);
		for (int c = 0; c < clusterCount; c++) {
			if (counts[c] == 0) {
				continue;
			}
			Lab center = new Lab(centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2]);
			clusters.add(new Cluster(center, counts[c], counts[c] / (double) n));
		}

		// Rank by share, then by the centroid itself. Leaving equal-share clusters in array order
		// would let them swap between runs, which is exactly what the fixed seed is meant to stop.
		clusters.sort(Comparator
			.comparingDouble(Cluster::share).reversed()
			.thenComparing(Comparator.comparingDouble((Cluster c) -> c.center().l()).reversed())
			.thenComparingDouble(c -> c.center().a())
			.thenComparingDouble(c -> c.center().b()));

		return new Result(List.copyOf(clusters), n, iteration, converged);
	}

	private static double[] seedPlusPlus(double[] lab, int n, int k, long seed) {
		Random random = new Random(seed);
		double[] centers = new double[k * 3];

		int first = random.nextInt(n);
		copyPoint(lab, first, centers, 0);

		double[] d2 = new double[n];
		for (int c = 1; c < k; c++) {
			double total = 0;
			for (int i = 0; i < n; i++) {
				d2[i] = nearestSquaredDistance(lab, i, centers, c);
				total += d2[i];
			}
			if (total <= 0) {
				// Every remaining point coincides with a chosen centre. Any pick is as good as any
				// other; take a stable one rather than consuming another random draw.
				copyPoint(lab, 0, centers, c);
				continue;
			}
			double target = random.nextDouble() * total;
			double accumulated = 0;
			int chosen = n - 1;
			for (int i = 0; i < n; i++) {
				accumulated += d2[i];
				if (accumulated > target) {
					chosen = i;
					break;
				}
			}
			copyPoint(lab, chosen, centers, c);
		}
		return centers;
	}

	private static void assign(double[] lab, int n, double[] centers, int k, int[] assignment) {
		for (int i = 0; i < n; i++) {
			double best = Double.MAX_VALUE;
			int bestIndex = 0;
			for (int c = 0; c < k; c++) {
				double distance = ColorDistance.squaredEuclidean(
					lab[i * 3], lab[i * 3 + 1], lab[i * 3 + 2],
					centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2]);
				if (distance < best) {
					best = distance;
					bestIndex = c;
				}
			}
			assignment[i] = bestIndex;
		}
	}

	/**
	 * Re-seed any cluster that ended an assignment pass empty, to the point furthest from its own
	 * centre. Deterministic on purpose: a random re-seed would break reproducibility, and dropping
	 * the cluster instead would silently change k part-way through the run.
	 */
	private static void reseedEmptyClusters(double[] lab, int n, double[] centers, int k, int[] assignment) {
		boolean[] used = new boolean[k];
		for (int i = 0; i < n; i++) {
			used[assignment[i]] = true;
		}
		for (int c = 0; c < k; c++) {
			if (used[c]) {
				continue;
			}
			int worst = -1;
			double worstDistance = -1;
			for (int i = 0; i < n; i++) {
				int owner = assignment[i];
				double distance = ColorDistance.squaredEuclidean(
					lab[i * 3], lab[i * 3 + 1], lab[i * 3 + 2],
					centers[owner * 3], centers[owner * 3 + 1], centers[owner * 3 + 2]);
				if (distance > worstDistance) {
					worstDistance = distance;
					worst = i;
				}
			}
			if (worst < 0 || worstDistance <= 0) {
				// Nothing to steal - every point already sits exactly on its centre.
				continue;
			}
			copyPoint(lab, worst, centers, c);
			assignment[worst] = c;
			used[c] = true;
		}
	}

	private static double nearestSquaredDistance(double[] lab, int point, double[] centers, int centerCount) {
		double best = Double.MAX_VALUE;
		for (int c = 0; c < centerCount; c++) {
			best = Math.min(best, ColorDistance.squaredEuclidean(
				lab[point * 3], lab[point * 3 + 1], lab[point * 3 + 2],
				centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2]));
		}
		return best;
	}

	private static void copyPoint(double[] lab, int point, double[] centers, int center) {
		centers[center * 3] = lab[point * 3];
		centers[center * 3 + 1] = lab[point * 3 + 1];
		centers[center * 3 + 2] = lab[point * 3 + 2];
	}
}
