package io.metaloom.cortex.node.whisper.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.whisper.WhisperOptions;

/**
 * AssertJ assertions for {@link WhisperOptions}.
 */
public class WhisperOptionsAssert extends AbstractCortexNodeOptionsAssert<WhisperOptionsAssert, WhisperOptions> {

	public WhisperOptionsAssert(WhisperOptions actual) {
		super(actual, WhisperOptionsAssert.class);
	}

	/**
	 * Assert that the modelPath is set to the expected value.
	 */
	public WhisperOptionsAssert hasModelPath(String expectedPath) {
		isNotNull();
		if (!expectedPath.equals(actual.getModelPath())) {
			failWithMessage("Expected modelPath to be '%s' but was '%s'", expectedPath, actual.getModelPath());
		}
		return this;
	}

	/**
	 * Assert that the modelPath is not empty.
	 */
	public WhisperOptionsAssert hasModelPath() {
		isNotNull();
		if (actual.getModelPath() == null || actual.getModelPath().isBlank()) {
			failWithMessage("Expected modelPath to be set but it was empty");
		}
		return this;
	}

	/**
	 * Assert that the temperature is set to the expected value.
	 */
	public WhisperOptionsAssert hasTemperature(float expectedTemp) {
		isNotNull();
		if (Float.compare(actual.getTemperature(), expectedTemp) != 0) {
			failWithMessage("Expected temperature to be %f but was %f", expectedTemp, actual.getTemperature());
		}
		return this;
	}

	/**
	 * Assert that the language is set to the expected value.
	 */
	public WhisperOptionsAssert hasLanguage(String expectedLanguage) {
		isNotNull();
		if (!expectedLanguage.equals(actual.getLanguage())) {
			failWithMessage("Expected language to be '%s' but was '%s'", expectedLanguage, actual.getLanguage());
		}
		return this;
	}

	/**
	 * Assert that GPU usage is enabled/disabled.
	 */
	public WhisperOptionsAssert hasUseGpu(boolean expected) {
		isNotNull();
		if (actual.isUseGpu() != expected) {
			failWithMessage("Expected useGpu to be %s but was %s", expected, actual.isUseGpu());
		}
		return this;
	}

	/**
	 * Assert that the GPU device is set to the expected value.
	 */
	public WhisperOptionsAssert hasGpuDevice(int expectedDevice) {
		isNotNull();
		if (actual.getGpuDevice() != expectedDevice) {
			failWithMessage("Expected gpuDevice to be %d but was %d", expectedDevice, actual.getGpuDevice());
		}
		return this;
	}
}
