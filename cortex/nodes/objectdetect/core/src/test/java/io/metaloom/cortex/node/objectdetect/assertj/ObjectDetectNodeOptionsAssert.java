package io.metaloom.cortex.node.objectdetect.assertj;

import java.util.Set;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNodeOptions;

/**
 * AssertJ assertions for {@link ObjectDetectNodeOptions}.
 */
public class ObjectDetectNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<ObjectDetectNodeOptionsAssert, ObjectDetectNodeOptions> {

	public ObjectDetectNodeOptionsAssert(ObjectDetectNodeOptions actual) {
		super(actual, ObjectDetectNodeOptionsAssert.class);
	}

	/**
	 * Assert that the modelPath is set to the expected value.
	 */
	public ObjectDetectNodeOptionsAssert hasModelPath(String expected) {
		isNotNull();
		if (!java.util.Objects.equals(actual.getModelPath(), expected)) {
			failWithMessage("Expected modelPath to be %s but was %s", expected, actual.getModelPath());
		}
		return this;
	}

	/**
	 * Assert that the labelsPath is set to the expected value.
	 */
	public ObjectDetectNodeOptionsAssert hasLabelsPath(String expected) {
		isNotNull();
		if (!java.util.Objects.equals(actual.getLabelsPath(), expected)) {
			failWithMessage("Expected labelsPath to be %s but was %s", expected, actual.getLabelsPath());
		}
		return this;
	}

	/**
	 * Assert whether GPU inference is requested.
	 */
	public ObjectDetectNodeOptionsAssert hasUseGpu(boolean expected) {
		isNotNull();
		if (actual.isUseGpu() != expected) {
			failWithMessage("Expected useGpu to be %s but was %s", expected, actual.isUseGpu());
		}
		return this;
	}

	/**
	 * Assert that the minConfidence is set to the expected value.
	 */
	public ObjectDetectNodeOptionsAssert hasMinConfidence(float expected) {
		isNotNull();
		if (actual.getMinConfidence() != expected) {
			failWithMessage("Expected minConfidence to be %s but was %s", expected, actual.getMinConfidence());
		}
		return this;
	}

	/**
	 * Assert that the videoChopRate is set to the expected value.
	 */
	public ObjectDetectNodeOptionsAssert hasVideoChopRate(int expected) {
		isNotNull();
		if (actual.getVideoChopRate() != expected) {
			failWithMessage("Expected videoChopRate to be %d but was %d", expected, actual.getVideoChopRate());
		}
		return this;
	}

	/**
	 * Assert that the videoScaleSize is set to the expected value.
	 */
	public ObjectDetectNodeOptionsAssert hasVideoScaleSize(int expected) {
		isNotNull();
		if (actual.getVideoScaleSize() != expected) {
			failWithMessage("Expected videoScaleSize to be %d but was %d", expected, actual.getVideoScaleSize());
		}
		return this;
	}

	/**
	 * Assert that the maxDetections is set to the expected value.
	 */
	public ObjectDetectNodeOptionsAssert hasMaxDetections(int expected) {
		isNotNull();
		if (actual.getMaxDetections() != expected) {
			failWithMessage("Expected maxDetections to be %d but was %d", expected, actual.getMaxDetections());
		}
		return this;
	}

	/**
	 * Assert the exact set of class names the filter keeps.
	 */
	public ObjectDetectNodeOptionsAssert hasClassFilter(Set<String> expected) {
		isNotNull();
		if (!actual.getClassFilter().equals(expected)) {
			failWithMessage("Expected classFilter to be %s but was %s", expected, actual.getClassFilter());
		}
		return this;
	}
}
