package io.metaloom.cortex.node.llm.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.llm.LLMNodeOptions;

/**
 * AssertJ assertions for {@link LLMNodeOptions}.
 */
public class LLMNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<LLMNodeOptionsAssert, LLMNodeOptions> {

	public LLMNodeOptionsAssert(LLMNodeOptions actual) {
		super(actual, LLMNodeOptionsAssert.class);
	}

	/**
	 * Assert that the ollamaUrl is set to the expected value.
	 */
	public LLMNodeOptionsAssert hasOllamaUrl(String expectedUrl) {
		isNotNull();
		if (!expectedUrl.equals(actual.ollamaUrl())) {
			failWithMessage("Expected ollamaUrl to be '%s' but was '%s'", expectedUrl, actual.ollamaUrl());
		}
		return this;
	}

	/**
	 * Assert that the ollamaUrl is not empty.
	 */
	public LLMNodeOptionsAssert hasOllamaUrl() {
		isNotNull();
		if (actual.ollamaUrl() == null || actual.ollamaUrl().isBlank()) {
			failWithMessage("Expected ollamaUrl to be set but it was empty");
		}
		return this;
	}

	/**
	 * Assert that the prompts map has the expected size.
	 */
	public LLMNodeOptionsAssert hasPromptCount(int expectedCount) {
		isNotNull();
		int actualCount = actual.getPrompts() != null ? actual.getPrompts().size() : 0;
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
		if (actual.getPrompts() == null || !actual.getPrompts().containsKey(promptId)) {
			failWithMessage("Expected prompts to contain '%s' but got: %s", promptId, actual.getPrompts());
		}
		return this;
	}

	/**
	 * Assert that the prompts map is not empty.
	 */
	public LLMNodeOptionsAssert hasPrompts() {
		isNotNull();
		if (actual.getPrompts() == null || actual.getPrompts().isEmpty()) {
			failWithMessage("Expected prompts to be non-empty but it was empty");
		}
		return this;
	}
}
