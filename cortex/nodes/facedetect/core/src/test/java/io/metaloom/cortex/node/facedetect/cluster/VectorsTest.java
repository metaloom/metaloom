package io.metaloom.cortex.node.facedetect.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure arithmetic - no model pack, no natives, no database.
 */
public class VectorsTest {

	private static final float TOLERANCE = 0.0001f;

	@Test
	public void testL2NormalizeProducesUnitLength() {
		float[] normalised = Vectors.l2normalize(new float[] { 3f, 4f });

		assertThat(normalised).containsExactly(new float[] { 0.6f, 0.8f }, org.assertj.core.data.Offset.offset(TOLERANCE));
		assertThat(Vectors.dot(normalised, normalised)).isCloseTo(1f, org.assertj.core.data.Offset.offset(TOLERANCE));
	}

	@Test
	public void testL2NormalizeDoesNotModifyTheInput() {
		float[] input = new float[] { 3f, 4f };
		Vectors.l2normalize(input);

		assertThat(input).containsExactly(3f, 4f);
	}

	/**
	 * A zero vector has no direction to scale. Returning NaN would be silently poisonous: NaN comparisons are false, so every distance involving it
	 * would quietly fall outside eps and the face would become its own subject for the wrong reason.
	 */
	@Test
	public void testL2NormalizeOfZeroVectorDoesNotProduceNaN() {
		float[] normalised = Vectors.l2normalize(new float[512]);

		assertThat(normalised).hasSize(512);
		for (float value : normalised) {
			assertThat(Float.isNaN(value)).as("no component may be NaN").isFalse();
		}
	}

	@Test
	public void testCosineDistanceOfOrthogonalUnitVectorsIsOne() {
		float[] a = Vectors.l2normalize(new float[] { 1f, 0f });
		float[] b = Vectors.l2normalize(new float[] { 0f, 1f });

		assertThat(Vectors.cosineDistance(a, b)).isCloseTo(1f, org.assertj.core.data.Offset.offset(TOLERANCE));
	}

	@Test
	public void testCosineDistanceOfIdenticalVectorsIsZero() {
		float[] a = Vectors.l2normalize(new float[] { 0.2f, 0.9f, -0.4f });

		assertThat(Vectors.cosineDistance(a, a)).isCloseTo(0f, org.assertj.core.data.Offset.offset(TOLERANCE));
	}

	@Test
	public void testCosineDistanceOfOppositeVectorsIsTwo() {
		float[] a = Vectors.l2normalize(new float[] { 1f, 0f });
		float[] b = Vectors.l2normalize(new float[] { -1f, 0f });

		assertThat(Vectors.cosineDistance(a, b)).isCloseTo(2f, org.assertj.core.data.Offset.offset(TOLERANCE));
	}

	/**
	 * Different dimensionality means different models, which is a configuration error rather than a distance to compute.
	 */
	@Test
	public void testDotRejectsMismatchedDimensions() {
		assertThatThrownBy(() -> Vectors.dot(new float[3], new float[4]))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("different models");
	}

	@Test
	public void testCentroidIsTheUnitMeanDirection() {
		float[] a = Vectors.l2normalize(new float[] { 1f, 0f });
		float[] b = Vectors.l2normalize(new float[] { 0f, 1f });

		float[] centroid = Vectors.centroid(new float[][] { a, b });

		// The bisector of two orthogonal unit vectors, itself unit length.
		assertThat(centroid).containsExactly(new float[] { 0.7071f, 0.7071f }, org.assertj.core.data.Offset.offset(TOLERANCE));
		assertThat(Vectors.dot(centroid, centroid)).isCloseTo(1f, org.assertj.core.data.Offset.offset(TOLERANCE));
	}

}
