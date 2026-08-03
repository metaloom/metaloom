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
	 * Assert that the openaiUrl is set to the expected value.
	 */
	public LLMNodeOptionsAssert hasOpenaiUrl(String expectedUrl) {
		isNotNull();
		if (!expectedUrl.equals(actual.openaiUrl())) {
			failWithMessage("Expected openaiUrl to be '%s' but was '%s'", expectedUrl, actual.openaiUrl());
		}
		return this;
	}

	/**
	 * Assert that the openaiUrl is not empty.
	 */
	public LLMNodeOptionsAssert hasOpenaiUrl() {
		isNotNull();
		if (actual.openaiUrl() == null || actual.openaiUrl().isBlank()) {
			failWithMessage("Expected openaiUrl to be set but it was empty");
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
