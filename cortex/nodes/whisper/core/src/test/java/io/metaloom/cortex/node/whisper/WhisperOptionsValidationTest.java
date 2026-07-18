package io.metaloom.cortex.node.whisper;

import static io.metaloom.cortex.node.whisper.assertj.WhisperNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class WhisperOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		WhisperOptions options = new WhisperOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testCustomModelPathValid() {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath("models/ggml-tiny.bin");
		assertThat(options)
			.isValid().hasModelPath("models/ggml-tiny.bin");
	}

	@Test
	public void testEmptyModelPathInvalid() {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath("");
		assertThat(options).isInvalid().hasErrorCount(1).hasError("modelPath must not be empty");
	}

	@Test
	public void testNullModelPathInvalid() {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath(null);
		assertThat(options).isInvalid().hasError("modelPath must not be empty");
	}

	@Test
	public void testNegativeTemperatureInvalid() {
		WhisperOptions options = new WhisperOptions();
		options.setTemperature(-1.0f);
		assertThat(options).isInvalid().hasError("temperature must be non-negative");
	}

	@Test
	public void testZeroTemperatureValid() {
		WhisperOptions options = new WhisperOptions();
		options.setTemperature(0.0f);
		assertThat(options)
			.isValid().hasTemperature(0.0f);
	}

	@Test
	public void testPositiveTemperatureValid() {
		WhisperOptions options = new WhisperOptions();
		options.setTemperature(0.5f);
		assertThat(options)
			.isValid().hasTemperature(0.5f);
	}

	@Test
	public void testNegativeTemperatureIncInvalid() {
		WhisperOptions options = new WhisperOptions();
		options.setTemperatureInc(-0.1f);
		assertThat(options).isInvalid().hasError("temperatureInc must be non-negative");
	}

	@Test
	public void testLanguageValid() {
		WhisperOptions options = new WhisperOptions();
		options.setLanguage("en");
		assertThat(options)
			.isValid().hasLanguage("en");
	}

	@Test
	public void testNegativeGpuDeviceInvalid() {
		WhisperOptions options = new WhisperOptions();
		options.setGpuDevice(-1);
		assertThat(options).isInvalid().hasError("gpuDevice must be non-negative");
	}

	@Test
	public void testGpuDeviceValid() {
		WhisperOptions options = new WhisperOptions();
		options.setGpuDevice(0);
		assertThat(options)
			.isValid().hasGpuDevice(0);
		
		options.setGpuDevice(1);
		assertThat(options)
			.isValid().hasGpuDevice(1);
	}

	@Test
	public void testUseGpuValid() {
		WhisperOptions options = new WhisperOptions();
		options.setUseGpu(true);
		assertThat(options)
			.isValid().hasUseGpu(true);
		
		options.setUseGpu(false);
		assertThat(options)
			.isValid().hasUseGpu(false);
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		WhisperOptions options = new WhisperOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		WhisperOptions options = new WhisperOptions();
		options.setModelPath("");
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("modelPath must not be empty");
		
		WhisperOptions validOptions = new WhisperOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}