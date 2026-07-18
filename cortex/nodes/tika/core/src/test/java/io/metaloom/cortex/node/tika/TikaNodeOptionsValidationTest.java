package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.node.tika.assertj.TikaNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class TikaNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		TikaNodeOptions options = new TikaNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		TikaNodeOptions options = new TikaNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		TikaNodeOptions options = new TikaNodeOptions();
		ValidationResult result = options.validate();
		assertThat(result).isValid().hasNoErrors();
	}
}