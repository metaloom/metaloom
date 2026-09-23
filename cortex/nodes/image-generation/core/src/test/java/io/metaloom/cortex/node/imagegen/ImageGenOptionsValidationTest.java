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
			.hasSteps(30)
			.hasEditEndpoint("/edit")
			.hasMaskEndpoint("/mask")
			.hasMaskPrompt("")
			.hasNegativePrompt("")
			.hasTrueCfgScale(1.0)
			.hasOutputResolution(1024)
			.hasComposite(false);
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

	@Test
	public void testEditOptionsValid() {
		ImageGenNodeOptions options = new ImageGenNodeOptions()
			.setMode(ImageGenMode.EDIT)
			.setPrompt("give him dark brown hair")
			.setPort(9230)
			.setMaskPrompt("the boy's hair")
			.setNegativePrompt("blurry, distorted")
			.setTrueCfgScale(4.0)
			.setOutputResolution(2048)
			.setComposite(true);
		assertThat(options).isValid()
			.hasMode(ImageGenMode.EDIT)
			.hasPort(9230)
			.hasMaskPrompt("the boy's hair")
			.hasNegativePrompt("blurry, distorted")
			.hasTrueCfgScale(4.0)
			.hasOutputResolution(2048)
			.hasComposite(true);
	}

	/**
	 * MASK has nothing to segment without a subject, and unlike EDIT it has no port to take one
	 * from at pipeline-start time - so this is the one new required field.
	 */
	@Test
	public void testMaskModeRejectsBlankMaskPrompt() {
		ImageGenNodeOptions options = new ImageGenNodeOptions()
			.setMode(ImageGenMode.MASK)
			.setPrompt("unused in this mode")
			.setMaskPrompt("   ");
		assertThat(options).isInvalid().hasError("maskPrompt must not be empty in MASK mode");
	}

	@Test
	public void testMaskModeAcceptsAMaskPrompt() {
		ImageGenNodeOptions options = new ImageGenNodeOptions()
			.setMode(ImageGenMode.MASK)
			.setPrompt("unused in this mode")
			.setMaskPrompt("the boy's hair");
		assertThat(options).isValid().hasMode(ImageGenMode.MASK).hasMaskPrompt("the boy's hair");
	}

	/**
	 * A blank maskPrompt is fine in every other mode: EDIT may be fed one through the mask port,
	 * and an edit with no mask at all is the ordinary multi-image compose.
	 */
	@Test
	public void testBlankMaskPromptIsFineOutsideMaskMode() {
		ImageGenNodeOptions options = new ImageGenNodeOptions()
			.setMode(ImageGenMode.EDIT)
			.setPrompt("combine these");
		assertThat(options).isValid();
	}

	@Test
	public void testTrueCfgScaleBelowOneRejected() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("a red apple").setTrueCfgScale(0.5);
		assertThat(options).isInvalid().hasError("trueCfgScale must be in [1, 10], got 0.5");
	}

	@Test
	public void testTrueCfgScaleAboveTenRejected() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("a red apple").setTrueCfgScale(12.0);
		assertThat(options).isInvalid().hasError("trueCfgScale must be in [1, 10], got 12.0");
	}

	/**
	 * 2752 is the longest side in the model card's aspect-ratio table. Above it the model is out of
	 * distribution, so this is a rejection rather than a clamp.
	 */
	@Test
	public void testOutputResolutionOutOfRangeRejected() {
		ImageGenNodeOptions options = new ImageGenNodeOptions().setPrompt("a red apple").setOutputResolution(4096);
		assertThat(options).isInvalid().hasError("outputResolution must be in [256, 2752], got 4096");

		ImageGenNodeOptions tooSmall = new ImageGenNodeOptions().setPrompt("a red apple").setOutputResolution(64);
		assertThat(tooSmall).isInvalid().hasError("outputResolution must be in [256, 2752], got 64");
	}
}
