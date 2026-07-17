package io.metaloom.cortex.node.whisper.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.whisper.WhisperOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link WhisperOptions}.
 */
public class WhisperOptionsAssert extends CortexNodeOptionsAssert {

	public WhisperOptionsAssert(WhisperOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as WhisperOptions.
	 */
	private WhisperOptions whisperOptions() {
		return (WhisperOptions) actual;
	}

	/**
	 * Assert that the modelPath is set to the expected value.
	 */
	public WhisperOptionsAssert hasModelPath(String expectedPath) {
		isNotNull();
		if (!expectedPath.equals(whisperOptions().getModelPath())) {
			failWithMessage("Expected modelPath to be '%s' but was '%s'", expectedPath, whisperOptions().getModelPath());
		}
		return this;
	}

	/**
	 * Assert that the modelPath is not empty.
	 */
	public WhisperOptionsAssert hasModelPath() {
		isNotNull();
		if (whisperOptions().getModelPath() == null || whisperOptions().getModelPath().isBlank()) {
			failWithMessage("Expected modelPath to be set but it was empty");
		}
		return this;
	}

	/**
	 * Assert that the temperature is set to the expected value.
	 */
	public WhisperOptionsAssert hasTemperature(float expectedTemp) {
		isNotNull();
		if (Float.compare(whisperOptions().getTemperature(), expectedTemp) != 0) {
			failWithMessage("Expected temperature to be %f but was %f", expectedTemp, whisperOptions().getTemperature());
		}
		return this;
	}

	/**
	 * Assert that the language is set to the expected value.
	 */
	public WhisperOptionsAssert hasLanguage(String expectedLanguage) {
		isNotNull();
		if (!expectedLanguage.equals(whisperOptions().getLanguage())) {
			failWithMessage("Expected language to be '%s' but was '%s'", expectedLanguage, whisperOptions().getLanguage());
		}
		return this;
	}

	/**
	 * Assert that GPU usage is enabled/disabled.
	 */
	public WhisperOptionsAssert hasUseGpu(boolean expected) {
		isNotNull();
		if (whisperOptions().isUseGpu() != expected) {
			failWithMessage("Expected useGpu to be %s but was %s", expected, whisperOptions().isUseGpu());
		}
		return this;
	}

	/**
	 * Assert that the GPU device is set to the expected value.
	 */
	public WhisperOptionsAssert hasGpuDevice(int expectedDevice) {
		isNotNull();
		if (whisperOptions().getGpuDevice() != expectedDevice) {
			failWithMessage("Expected gpuDevice to be %d but was %d", expectedDevice, whisperOptions().getGpuDevice());
		}
		return this;
	}
}