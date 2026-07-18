package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.node.hash.assertj.HashNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class HashNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		HashNodeOptions options = new HashNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testAllAlgorithmsEnabled() {
		HashNodeOptions options = new HashNodeOptions();
		options.setMD5(true);
		options.setSHA256(true);
		options.setSHA512(true);
		options.setChunkHash(true);
		assertThat(options).isValid();
	}

	@Test
	public void testOnlyMD5Enabled() {
		HashNodeOptions options = new HashNodeOptions();
		options.setMD5(true);
		options.setSHA256(false);
		options.setSHA512(false);
		options.setChunkHash(false);
		assertThat(options)
			.isValid().hasMD5(true).hasSHA256(false).hasSHA512(false).hasChunkHash(false);
	}

	@Test
	public void testOnlySHA512Enabled() {
		HashNodeOptions options = new HashNodeOptions();
		options.setMD5(false);
		options.setSHA256(false);
		options.setSHA512(true);
		options.setChunkHash(false);
		assertThat(options)
			.isValid().hasMD5(false).hasSHA256(false).hasSHA512(true).hasChunkHash(false);
	}

	@Test
	public void testNoAlgorithmsEnabledInvalid() {
		HashNodeOptions options = new HashNodeOptions();
		options.setMD5(false);
		options.setSHA256(false);
		options.setSHA512(false);
		options.setChunkHash(false);
		assertThat(options).isInvalid().hasErrorCount(1).hasError("At least one hash algorithm");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		HashNodeOptions options = new HashNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testZeroTimeoutValid() {
		HashNodeOptions options = new HashNodeOptions();
		options.setTimeoutMs(0);
		assertThat(options).isValid();
	}

	@Test
	public void testPositiveTimeoutValid() {
		HashNodeOptions options = new HashNodeOptions();
		options.setTimeoutMs(30000);
		assertThat(options).isValid();
	}

	@Test
	public void testValidationResultDirect() {
		HashNodeOptions options = new HashNodeOptions();
		options.setMD5(false);
		options.setSHA256(false);
		options.setSHA512(false);
		options.setChunkHash(false);
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("At least one hash algorithm");
		
		HashNodeOptions validOptions = new HashNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}