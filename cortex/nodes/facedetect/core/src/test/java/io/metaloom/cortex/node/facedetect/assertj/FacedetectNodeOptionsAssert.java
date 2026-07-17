package io.metaloom.cortex.node.facedetect.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link FacedetectNodeOptions}.
 */
public class FacedetectNodeOptionsAssert extends CortexNodeOptionsAssert {

	public FacedetectNodeOptionsAssert(FacedetectNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as FacedetectNodeOptions.
	 */
	private FacedetectNodeOptions faceOptions() {
		return (FacedetectNodeOptions) actual;
	}

	/**
	 * Assert that the videoChopRate is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasVideoChopRate(int expectedRate) {
		isNotNull();
		if (faceOptions().getVideoChopRate() != expectedRate) {
			failWithMessage("Expected videoChopRate to be %d but was %d", expectedRate, faceOptions().getVideoChopRate());
		}
		return this;
	}

	/**
	 * Assert that the videoScaleSize is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasVideoScaleSize(int expectedSize) {
		isNotNull();
		if (faceOptions().getVideoScaleSize() != expectedSize) {
			failWithMessage("Expected videoScaleSize to be %d but was %d", expectedSize, faceOptions().getVideoScaleSize());
		}
		return this;
	}

	/**
	 * Assert that the faceClusterMinimum is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasFaceClusterMinimum(int expectedMinimum) {
		isNotNull();
		if (faceOptions().getFaceClusterMinimum() != expectedMinimum) {
			failWithMessage("Expected faceClusterMinimum to be %d but was %d", expectedMinimum, faceOptions().getFaceClusterMinimum());
		}
		return this;
	}

	/**
	 * Assert that the faceClusterEPS is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasFaceClusterEPS(float expectedEPS) {
		isNotNull();
		if (Float.compare(faceOptions().getFaceClusterEPS(), expectedEPS) != 0) {
			failWithMessage("Expected faceClusterEPS to be %f but was %f", expectedEPS, faceOptions().getFaceClusterEPS());
		}
		return this;
	}

	/**
	 * Assert that the minFaceHeightFactor is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasMinFaceHeightFactor(float expectedFactor) {
		isNotNull();
		if (Float.compare(faceOptions().getMinFaceHeightFactor(), expectedFactor) != 0) {
			failWithMessage("Expected minFaceHeightFactor to be %f but was %f", expectedFactor, faceOptions().getMinFaceHeightFactor());
		}
		return this;
	}

	/**
	 * Assert that the inspirefacePackPath is set to the expected value.
	 */
	public FacedetectNodeOptionsAssert hasInspirefacePackPath(String expectedPath) {
		isNotNull();
		if (!expectedPath.equals(faceOptions().getInspirefacePackPath())) {
			failWithMessage("Expected inspirefacePackPath to be '%s' but was '%s'", expectedPath, faceOptions().getInspirefacePackPath());
		}
		return this;
	}

	/**
	 * Assert that the capabilities set contains the expected capability.
	 */
	public FacedetectNodeOptionsAssert hasCapability(io.metaloom.cortex.node.facedetect.FacedetectNodeCapabilities capability) {
		isNotNull();
		if (faceOptions().getCapabilities() == null || !faceOptions().getCapabilities().contains(capability)) {
			failWithMessage("Expected capabilities to contain '%s' but got: %s", capability, faceOptions().getCapabilities());
		}
		return this;
	}
}