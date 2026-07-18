package io.metaloom.cortex.node.facedetect.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;

/**
 * AssertJ assertions for {@link FacedetectNodeOptions}.
 */
public class FacedetectNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<FacedetectNodeOptionsAssert, FacedetectNodeOptions> {

	public FacedetectNodeOptionsAssert(FacedetectNodeOptions actual) {
		super(actual, FacedetectNodeOptionsAssert.class);
	}

	/**
	 * Assert that the videoChopRate is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasVideoChopRate(int expectedRate) {
		isNotNull();
		if (actual.getVideoChopRate() != expectedRate) {
			failWithMessage("Expected videoChopRate to be %d but was %d", expectedRate, actual.getVideoChopRate());
		}
		return this;
	}

	/**
	 * Assert that the videoScaleSize is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasVideoScaleSize(int expectedSize) {
		isNotNull();
		if (actual.getVideoScaleSize() != expectedSize) {
			failWithMessage("Expected videoScaleSize to be %d but was %d", expectedSize, actual.getVideoScaleSize());
		}
		return this;
	}

	/**
	 * Assert that the faceClusterMinimum is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasFaceClusterMinimum(int expectedMinimum) {
		isNotNull();
		if (actual.getFaceClusterMinimum() != expectedMinimum) {
			failWithMessage("Expected faceClusterMinimum to be %d but was %d", expectedMinimum, actual.getFaceClusterMinimum());
		}
		return this;
	}

	/**
	 * Assert that the faceClusterEPS is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasFaceClusterEPS(float expectedEPS) {
		isNotNull();
		if (Float.compare(actual.getFaceClusterEPS(), expectedEPS) != 0) {
			failWithMessage("Expected faceClusterEPS to be %f but was %f", expectedEPS, actual.getFaceClusterEPS());
		}
		return this;
	}

	/**
	 * Assert that the minFaceHeightFactor is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasMinFaceHeightFactor(float expectedFactor) {
		isNotNull();
		if (Float.compare(actual.getMinFaceHeightFactor(), expectedFactor) != 0) {
			failWithMessage("Expected minFaceHeightFactor to be %f but was %f", expectedFactor, actual.getMinFaceHeightFactor());
		}
		return this;
	}

	/**
	 * Assert that the inspirefacePackPath is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasInspirefacePackPath(String expectedPath) {
		isNotNull();
		if (!expectedPath.equals(actual.getInspirefacePackPath())) {
			failWithMessage("Expected inspirefacePackPath to be '%s' but was '%s'", expectedPath, actual.getInspirefacePackPath());
		}
		return this;
	}

	/**
	 * Assert that the capabilities set contains the expected capability.
	 */
	public FacedetectNodeOptionsAssert hasCapability(io.metaloom.cortex.node.facedetect.FacedetectNodeCapabilities capability) {
		isNotNull();
		if (actual.getCapabilities() == null || !actual.getCapabilities().contains(capability)) {
			failWithMessage("Expected capabilities to contain '%s' but got: %s", capability, actual.getCapabilities());
		}
		return this;
	}
}
