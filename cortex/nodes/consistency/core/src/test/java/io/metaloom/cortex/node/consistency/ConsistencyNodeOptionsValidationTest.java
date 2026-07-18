package io.metaloom.cortex.node.consistency;

import static io.metaloom.cortex.node.consistency.assertj.ConsistencyNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class ConsistencyNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		ConsistencyNodeOptions options = new ConsistencyNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		ConsistencyNodeOptions options = new ConsistencyNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		ConsistencyNodeOptions options = new ConsistencyNodeOptions();
		ValidationResult result = options.validate();
		assertThat(result).isValid().hasNoErrors();
	}
}