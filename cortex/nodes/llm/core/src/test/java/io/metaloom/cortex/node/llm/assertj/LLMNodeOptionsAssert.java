package io.metaloom.cortex.node.llm.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.llm.LLMNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link LLMNodeOptions}.
 */
public class LLMNodeOptionsAssert extends CortexNodeOptionsAssert {

	public LLMNodeOptionsAssert(LLMNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as LLMNodeOptions.
	 */
	private LLMNodeOptions llmOptions() {
		return (LLMNodeOptions) actual;
	}

	/**
	 * Assert that the ollamaUrl is set to the expected value.
	 */
	public LLMNodeOptionsAssert hasOllamaUrl(String expectedUrl) {
		isNotNull();
		if (!expectedUrl.equals(llmOptions().ollamaUrl())) {
			failWithMessage("Expected ollamaUrl to be '%s' but was '%s'", expectedUrl, llmOptions().ollamaUrl());
		}
		return this;
	}

	/**
	 * Assert that the ollamaUrl is not empty.
	 */
	public LLMNodeOptionsAssert hasOllamaUrl() {
		isNotNull();
		if (llmOptions().ollamaUrl() == null || llmOptions().ollamaUrl().isBlank()) {
			failWithMessage("Expected ollamaUrl to be set but it was empty");
		}
		return this;
	}

	/**
	 * Assert that the prompts map has the expected size.
	 */
	public LLMNodeOptionsAssert hasPromptCount(int expectedCount) {
		isNotNull();
		int actualCount = llmOptions().getPrompts() != null ? llmOptions().getPrompts().size() : 0;
		if (actualCount != expectedCount) {
			failWithMessage("Expected %d prompts but got %d", expectedCount, actualCount);
		}
		return this;
	}

	/**
	 * Assert that the prompts map contains a specific prompt ID.
	 */
	public LLMNodeOptionsAssert hasPrompt(String promptId) {
		isNotNull();
		if (llmOptions().getPrompts() == null || !llmOptions().getPrompts().containsKey(promptId)) {
			failWithMessage("Expected prompts to contain '%s' but got: %s", promptId, llmOptions().getPrompts());
		}
		return this;
	}

	/**
	 * Assert that the prompts map is not empty.
	 */
	public LLMNodeOptionsAssert hasPrompts() {
		isNotNull();
		if (llmOptions().getPrompts() == null || llmOptions().getPrompts().isEmpty()) {
			failWithMessage("Expected prompts to be non-empty but it was empty");
		}
		return this;
	}
}