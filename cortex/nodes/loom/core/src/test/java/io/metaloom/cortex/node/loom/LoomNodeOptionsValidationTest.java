package io.metaloom.cortex.node.loom;

import static io.metaloom.cortex.node.loom.assertj.LoomNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class LoomNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		LoomNodeOptions options = new LoomNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		LoomNodeOptions options = new LoomNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		LoomNodeOptions options = new LoomNodeOptions();
		ValidationResult result = options.validate();
		assertThat(result).isValid().hasNoErrors();
	}
}