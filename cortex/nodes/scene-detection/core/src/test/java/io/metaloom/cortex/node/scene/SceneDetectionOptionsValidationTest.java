package io.metaloom.cortex.node.scene;

import static io.metaloom.cortex.api.option.assertj.OptionsAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class SceneDetectionOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		SceneDetectionOptions options = new SceneDetectionOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		SceneDetectionOptions options = new SceneDetectionOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		SceneDetectionOptions options = new SceneDetectionOptions();
		ValidationResult result = options.validate();
		assertThat(result).isValid().hasNoErrors();
	}
}