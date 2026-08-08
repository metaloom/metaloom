package io.metaloom.cortex.node.facedetect.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * DBSCAN over a precomputed dense distance matrix.
 *
 * <p>
 * Density-based rather than centroid-based on purpose: the number of distinct people in a video is exactly what is being asked, so an algorithm that
 * has to be told k is the wrong shape. DBSCAN also refuses to force an outlier into a group, which matters here - a half-turned face that matches
 * nobody should be reported as its own subject, not folded into whoever it is nearest.
 * </p>
 *
 * <p>
 * The matrix is dense and O(N²) because N is small: a still has a handful of faces and the video scanner caps its output at a few dozen. A spatial
 * index or an ANN structure would cost more to build than the scan it saves.
 * </p>
 *
 * <p>
 * This class knows nothing about faces, embeddings or Loom - it takes distances and returns labels, which is what makes it testable with no model
 * pack, no native library and no database.
 * </p>
 */
public final class Dbscan {

	/** Label of a point that belongs to no cluster. */
	public static final int NOISE = -1;

	private Dbscan() {
	}

	/**
	 * Assign each point a cluster label.
	 *
	 * <p>
	 * <strong>{@code minPoints} counts the point itself.</strong> So {@code minPoints = 1} makes every point its own core point and nothing is ever
	 * noise, and the useful minimum of {@code 2} means "a point needs at least one neighbour within eps to seed a cluster". This is the standard
	 * definition but it is the detail people most often get backwards, and getting it wrong shifts every result by one.
	 * </p>
	 *
	 * @param distances a symmetric {@code n x n} distance matrix
	 * @param eps       neighbourhood radius; points at exactly this distance are neighbours
	 * @param minPoints minimum neighbourhood size for a core point, counting the point itself
	 * @return one label per point: a cluster ordinal from 0, or {@link #NOISE}
	 */
	public static int[] cluster(float[][] distances, float eps, int minPoints) {
		if (distances == null) {
			throw new IllegalArgumentException("Cannot cluster a null distance matrix");
		}
		int n = distances.length;
		int[] labels = new int[n];
		Arrays.fill(labels, NOISE);
		if (n == 0) {
			return labels;
		}

		boolean[] visited = new boolean[n];
		int clusterId = 0;

		for (int point = 0; point < n; point++) {
			if (visited[point]) {
				continue;
			}
			visited[point] = true;

			List<Integer> neighbours = neighbours(distances, point, eps);
			if (neighbours.size() < minPoints) {
				// Not a core point. It stays NOISE for now but may still be absorbed later as a border point of a cluster grown from elsewhere.
				continue;
			}

			labels[point] = clusterId;
			// Iterative rather than recursive: a chain of faces that are each similar to the next can be as long as the input, and a recursive expand
			// would put that chain on the JVM stack.
			Deque<Integer> queue = new ArrayDeque<>(neighbours);
			while (!queue.isEmpty()) {
				int candidate = queue.poll();
				if (!visited[candidate]) {
					visited[candidate] = true;
					List<Integer> candidateNeighbours = neighbours(distances, candidate, eps);
					if (candidateNeighbours.size() >= minPoints) {
						// A core point: the cluster grows through it.
						queue.addAll(candidateNeighbours);
					}
				}
				if (labels[candidate] == NOISE) {
					// Either unclaimed, or previously written off as noise and now reachable from a core point - a border point either way.
					labels[candidate] = clusterId;
				}
			}
			clusterId++;
		}
		return labels;
	}

	/**
	 * Build the symmetric distance matrix for a set of vectors.
	 *
	 * @param vectors the points, all of equal length
	 * @param metric  the distance to apply to each pair
	 */
	public static float[][] distanceMatrix(float[][] vectors, DistanceFn metric) {
		int n = vectors.length;
		float[][] distances = new float[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				float distance = metric.distance(vectors[i], vectors[j]);
				distances[i][j] = distance;
				distances[j][i] = distance;
			}
		}
		return distances;
	}

	private static List<Integer> neighbours(float[][] distances, int point, float eps) {
		List<Integer> found = new ArrayList<>();
		float[] row = distances[point];
		for (int i = 0; i < row.length; i++) {
			if (row[i] <= eps) {
				// Includes the point itself, whose distance to itself is 0 - which is why minPoints counts it.
				found.add(i);
			}
		}
		return found;
	}

	/** A distance between two equal-length vectors. */
	@FunctionalInterface
	public interface DistanceFn {
		float distance(float[] a, float[] b);
	}

}
