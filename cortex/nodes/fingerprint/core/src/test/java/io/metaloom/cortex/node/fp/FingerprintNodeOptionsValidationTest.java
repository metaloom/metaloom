package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.api.option.assertj.OptionsAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class FingerprintNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		FingerprintNodeOptions options = new FingerprintNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		FingerprintNodeOptions options = new FingerprintNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		FingerprintNodeOptions options = new FingerprintNodeOptions();
		ValidationResult result = options.validate();
		assertThat(result).isValid().hasNoErrors();
	}
}