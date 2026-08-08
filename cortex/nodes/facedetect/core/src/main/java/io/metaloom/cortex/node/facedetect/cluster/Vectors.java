package io.metaloom.cortex.node.facedetect.cluster;

/**
 * Vector arithmetic for recognition embeddings: L2 normalisation, dot product and cosine distance.
 *
 * <p>
 * Deliberately tiny and dependency-free. There was no cosine helper anywhere in the tree to reuse - an earlier draft of the face workflow spec claimed
 * {@code Face.cosineSimilarity} existed in {@code facedetect4j}, and it does not; the string appears only in javadoc prose. The only other clustering
 * code in the repository is {@code LabKMeans} in the dominant-colour node, which quantises colours in Lab space and shares nothing with this.
 * </p>
 *
 * <p>
 * The whole point of normalising up front is that cosine similarity between unit vectors <em>is</em> the dot product, so the O(n²) distance matrix
 * costs one multiply-add per dimension per pair and no square roots at all.
 * </p>
 */
public final class Vectors {

	private Vectors() {
	}

	/**
	 * Scale a vector to unit length.
	 *
	 * <p>
	 * A zero vector, or one whose norm overflows to infinity, is returned as a copy of itself rather than as a vector of {@code NaN}. A single
	 * degenerate embedding would otherwise poison every distance it participates in, and {@code NaN} comparisons are silently false - the cluster would
	 * not fail, it would quietly come out wrong.
	 * </p>
	 *
	 * @param vector the vector to normalise; not modified
	 * @return a new unit-length vector, or a copy of the input when it has no usable direction
	 */
	public static float[] l2normalize(float[] vector) {
		if (vector == null) {
			throw new IllegalArgumentException("Cannot normalise a null vector");
		}
		double sum = 0d;
		for (float value : vector) {
			sum += (double) value * value;
		}
		double norm = Math.sqrt(sum);
		if (norm == 0d || !Double.isFinite(norm)) {
			return vector.clone();
		}
		float[] out = new float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			out[i] = (float) (vector[i] / norm);
		}
		return out;
	}

	/**
	 * Dot product of two vectors of equal length.
	 *
	 * @throws IllegalArgumentException when the lengths differ - two embeddings of different dimensionality come from different models and are not
	 *                                  comparable at all, so this is a configuration error rather than a number to compute
	 */
	public static float dot(float[] a, float[] b) {
		if (a == null || b == null) {
			throw new IllegalArgumentException("Cannot compute a dot product with a null vector");
		}
		if (a.length != b.length) {
			throw new IllegalArgumentException(
				"Vectors of different dimensions are not comparable: " + a.length + " vs " + b.length + ". They come from different models.");
		}
		double sum = 0d;
		for (int i = 0; i < a.length; i++) {
			sum += (double) a[i] * b[i];
		}
		return (float) sum;
	}

	/**
	 * Cosine distance between two <strong>already L2-normalised</strong> vectors.
	 *
	 * <p>
	 * {@code 1 - cos(theta)}: 0 for identical directions, 1 for orthogonal, 2 for opposite. Passing un-normalised vectors does not throw, it just
	 * returns a number that is not a cosine distance - {@link #l2normalize(float[])} first.
	 * </p>
	 */
	public static float cosineDistance(float[] a, float[] b) {
		return 1f - dot(a, b);
	}

	/**
	 * The unit-length mean direction of a set of vectors.
	 *
	 * @param vectors the members; must be non-empty and of equal length
	 * @return the normalised centroid
	 */
	public static float[] centroid(float[][] vectors) {
		if (vectors == null || vectors.length == 0) {
			throw new IllegalArgumentException("Cannot compute a centroid of nothing");
		}
		int dimensions = vectors[0].length;
		double[] sum = new double[dimensions];
		for (float[] vector : vectors) {
			if (vector.length != dimensions) {
				throw new IllegalArgumentException(
					"Vectors of different dimensions are not comparable: " + vector.length + " vs " + dimensions + ". They come from different models.");
			}
			for (int i = 0; i < dimensions; i++) {
				sum[i] += vector[i];
			}
		}
		float[] mean = new float[dimensions];
		for (int i = 0; i < dimensions; i++) {
			mean[i] = (float) (sum[i] / vectors.length);
		}
		return l2normalize(mean);
	}

}
