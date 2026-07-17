package io.metaloom.cortex.node.llm;

import static io.metaloom.cortex.api.option.assertj.OptionsAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class LLMNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsInvalidNoPrompts() {
		LLMNodeOptions options = new LLMNodeOptions();
		assertThat(options).isInvalid().hasError("prompts must not be empty");
	}

	@Test
	public void testWithPromptsValid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setPrompts(java.util.Map.of("test", new LLMNodePrompt()));
		assertThat(options).isValid();
	}

	@Test
	public void testEmptyOllamaUrlInvalid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setOllamaUrl("");
		options.setPrompts(java.util.Map.of("test", new LLMNodePrompt()));
		assertThat(options).isInvalid().hasErrorCount(1).hasError("ollamaUrl must not be empty");
	}

	@Test
	public void testNullOllamaUrlInvalid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setOllamaUrl(null);
		options.setPrompts(java.util.Map.of("test", new LLMNodePrompt()));
		assertThat(options).isInvalid().hasError("ollamaUrl must not be empty");
	}

	@Test
	public void testCustomOllamaUrlValid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setOllamaUrl("http://localhost:11434");
		options.setPrompts(java.util.Map.of("test", new LLMNodePrompt()));
		io.metaloom.cortex.node.llm.assertj.LLMNodeAssertions.assertThat(options)
			.isValid().hasOllamaUrl("http://localhost:11434");
	}

	@Test
	public void testMultiplePromptsValid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setPrompts(java.util.Map.of(
			"prompt1", new LLMNodePrompt(),
			"prompt2", new LLMNodePrompt(),
			"prompt3", new LLMNodePrompt()
		));
		io.metaloom.cortex.node.llm.assertj.LLMNodeAssertions.assertThat(options)
			.isValid().hasPromptCount(3).hasPrompt("prompt1").hasPrompt("prompt2").hasPrompt("prompt3");
	}

	@Test
	public void testEmptyPromptsInvalid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setPrompts(java.util.Map.of());
		assertThat(options).isInvalid().hasErrorCount(1).hasError("prompts must not be empty");
	}

	@Test
	public void testNullPromptsInvalid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setPrompts(null);
		assertThat(options).isInvalid().hasError("prompts must not be empty");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setPrompts(java.util.Map.of("test", new LLMNodePrompt()));
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		LLMNodeOptions options = new LLMNodeOptions();
		options.setOllamaUrl("");
		options.setPrompts(java.util.Map.of("test", new LLMNodePrompt()));
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("ollamaUrl must not be empty");
		
		LLMNodeOptions validOptions = new LLMNodeOptions();
		validOptions.setPrompts(java.util.Map.of("test", new LLMNodePrompt()));
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}