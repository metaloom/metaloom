package io.metaloom.cortex.node.imagegen;

import static io.metaloom.cortex.node.imagegen.assertj.ImageGenNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class ImageGenOptionsValidationTest {

	@Test
	public void testDefaultOptionsValidAfterPrompt() {
		// A prompt is required; the rest of the defaults are valid.
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("a red apple");
		assertThat(options).isValid()
			.hasMode(ImageGenMode.GENERATE)
			.hasPrompt("a red apple")
			.hasHost("localhost")
			.hasPort(9200)
			.hasWidth(1024)
			.hasHeight(1024)
			.hasSteps(30);
	}

	@Test
	public void testCustomOptionsValid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions()
			.setMode(ImageGenMode.REMIX)
			.setPrompt("cyberpunk city")
			.setHost("imagegen.internal")
			.setPort(9300)
			.setWidth(768)
			.setHeight(768)
			.setStrength(0.5)
			.setSteps(40);
		assertThat(options).isValid()
			.hasMode(ImageGenMode.REMIX)
			.hasPrompt("cyberpunk city")
			.hasHost("imagegen.internal")
			.hasPort(9300)
			.hasWidth(768)
			.hasHeight(768)
			.hasStrength(0.5)
			.hasSteps(40);
	}

	@Test
	public void testEmptyPromptInvalid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions();
		assertThat(options).isInvalid().hasError("prompt must not be empty");
	}

	@Test
	public void testEmptyHostInvalid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("x").setHost("");
		assertThat(options).isInvalid().hasError("host must not be empty");
	}

	@Test
	public void testNonPositivePortInvalid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("x").setPort(0);
		assertThat(options).isInvalid().hasError("port must be positive, got 0");
	}

	@Test
	public void testNonPositiveWidthInvalid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("x").setWidth(0);
		assertThat(options).isInvalid().hasError("width must be positive, got 0");
	}

	@Test
	public void testNonPositiveStepsInvalid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("x").setSteps(-1);
		assertThat(options).isInvalid().hasError("steps must be positive, got -1");
	}

	@Test
	public void testStrengthOutOfRangeInvalid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("x").setStrength(1.5);
		assertThat(options).isInvalid().hasError("strength must be in (0, 1], got 1.5");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("x");
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative, got -1");
	}

	@Test
	public void testValidationResultDirect() {
		ImageGenNodeOptions valid = new ImageGenNodeOptions().setPrompt("x");
		ValidationResult validResult = valid.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}
