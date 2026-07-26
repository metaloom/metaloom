package io.metaloom.cortex.node.vlm;

import static io.metaloom.cortex.node.vlm.assertj.VlmNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class VlmNodeOptionsValidationTest {

	/**
	 * Bare defaults carry no prompt yet - the node installs the olmOCR preset in its constructor - so validating an untouched options object must flag the
	 * empty prompt map rather than silently pass.
	 */
	@Test
	public void testDefaultOptionsHaveNoPrompts() {
		VlmNodeOptions options = new VlmNodeOptions();
		assertThat(options)
			.hasEndpointUrl(VlmNodeOptions.DEFAULT_ENDPOINT_URL)
			.isInvalid().hasError("prompts must not be empty");
	}

	@Test
	public void testOlmOcrPresetIsValid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt(VlmPromptPresets.OLMOCR_ID, VlmPromptPresets.olmOcr());
		assertThat(options)
			.isValid()
			.hasPromptCount(1)
			.hasPromptModel(VlmPromptPresets.OLMOCR_ID, VlmPromptPresets.OLMOCR_MODEL);
	}

	@Test
	public void testEmptyEndpointUrlInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.setEndpointUrl("")
			.addPrompt("p", VlmPromptPresets.olmOcr());
		assertThat(options).isInvalid().hasErrorCount(1).hasError("endpointUrl must not be empty");
	}

	@Test
	public void testNullEndpointUrlInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.setEndpointUrl(null)
			.addPrompt("p", VlmPromptPresets.olmOcr());
		assertThat(options).isInvalid().hasError("endpointUrl must not be empty");
	}

	@Test
	public void testPromptWithoutModelInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt("p", new VlmNodePrompt().setPrompt("Describe the image"));
		assertThat(options).isInvalid().hasError("prompt 'p' must define a model");
	}

	@Test
	public void testPromptWithoutTextInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt("p", new VlmNodePrompt().setModel("some-model"));
		assertThat(options).isInvalid().hasError("prompt 'p' must define a prompt text");
	}

	@Test
	public void testNonPositiveMaxTokensInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt("p", VlmPromptPresets.olmOcr().setMaxTokens(0));
		assertThat(options).isInvalid().hasError("prompt 'p' maxTokens must be positive");
	}

	@Test
	public void testNegativeMaxImageDimInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt("p", VlmPromptPresets.olmOcr().setMaxImageDim(-1));
		assertThat(options).isInvalid().hasError("prompt 'p' maxImageDim must be non-negative");
	}

	/**
	 * A maxImageDim of 0 is the documented "do not scale" setting, not an error.
	 */
	@Test
	public void testZeroMaxImageDimValid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt("p", VlmPromptPresets.olmOcr().setMaxImageDim(0));
		assertThat(options).isValid();
	}

	@Test
	public void testNegativeTemperatureInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt("p", VlmPromptPresets.olmOcr().setTemperature(-0.5));
		assertThat(options).isInvalid().hasError("prompt 'p' temperature must be non-negative");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		VlmNodeOptions options = new VlmNodeOptions()
			.addPrompt("p", VlmPromptPresets.olmOcr());
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		VlmNodeOptions options = new VlmNodeOptions()
			.setEndpointUrl("")
			.addPrompt("p", VlmPromptPresets.olmOcr());

		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("endpointUrl must not be empty");

		VlmNodeOptions validOptions = new VlmNodeOptions().addPrompt("p", VlmPromptPresets.olmOcr());
		assertThat(validOptions.validate()).isValid().hasNoErrors();
	}
}
