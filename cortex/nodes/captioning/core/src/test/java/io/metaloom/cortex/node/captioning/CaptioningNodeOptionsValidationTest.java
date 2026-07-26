package io.metaloom.cortex.node.captioning;

import static io.metaloom.cortex.node.captioning.assertj.CaptioningNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class CaptioningNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testCustomSmolVLMHostValid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setSmolVLMHost("custom-host");
		assertThat(options)
			.isValid().hasSmolVLMHost("custom-host");
	}

	@Test
	public void testEmptySmolVLMHostInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setSmolVLMHost("");
		assertThat(options).isInvalid().hasErrorCount(1).hasError("smolVLMHost must not be empty");
	}

	@Test
	public void testNullSmolVLMHostInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setSmolVLMHost(null);
		assertThat(options).isInvalid().hasError("smolVLMHost must not be empty");
	}

	@Test
	public void testCustomSmolVLMPortValid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setSmolVLMPort(9000);
		assertThat(options)
			.isValid().hasSmolVLMPort(9000);
	}

	@Test
	public void testZeroSmolVLMPortInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setSmolVLMPort(0);
		assertThat(options).isInvalid().hasError("smolVLMPort must be positive");
	}

	@Test
	public void testNegativeSmolVLMPortInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setSmolVLMPort(-1);
		assertThat(options).isInvalid().hasError("smolVLMPort must be positive");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setSmolVLMHost("");

		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("smolVLMHost must not be empty");

		CaptioningNodeOptions validOptions = new CaptioningNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}

	// -- Video captioning options -------------------------------------------

	@Test
	public void testDefaultVideoStrategyIsWhole() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		assertThat(options).isValid().hasVideoStrategy(VideoCaptioningStrategy.WHOLE);
	}

	@Test
	public void testCustomVideoStrategyValid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setVideoStrategy(VideoCaptioningStrategy.SCENE);
		assertThat(options).isValid().hasVideoStrategy(VideoCaptioningStrategy.SCENE);
	}

	@Test
	public void testCustomVideoEndpointValid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setVideoEndpointUrl("http://vllm:8000").setVideoModel("qwen25vl");
		assertThat(options).isValid().hasVideoEndpointUrl("http://vllm:8000").hasVideoModel("qwen25vl");
	}

	@Test
	public void testEmptyVideoEndpointInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setVideoEndpointUrl("");
		assertThat(options).isInvalid().hasError("videoEndpointUrl must not be empty");
	}

	@Test
	public void testEmptyVideoModelInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setVideoModel("");
		assertThat(options).isInvalid().hasError("videoModel must not be empty");
	}

	@Test
	public void testZeroFrameCountInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setFrameCount(0);
		assertThat(options).isInvalid().hasError("frameCount must be positive");
	}

	@Test
	public void testNegativeTargetFrameSizeInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setTargetFrameSize(-1);
		assertThat(options).isInvalid().hasError("targetFrameSize must be positive");
	}

	@Test
	public void testZeroMaxTokensInvalid() {
		CaptioningNodeOptions options = new CaptioningNodeOptions();
		options.setMaxTokens(0);
		assertThat(options).isInvalid().hasError("maxTokens must be positive");
	}
}