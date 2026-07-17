package io.metaloom.cortex.node.llm.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.llm.LLMNodeOptions;

/**
 * Entry point for LLM node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.llm.assertj.LLMNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasOllamaUrl("http://localhost:11434").hasPromptCount(2);
 * </pre>
 */
public class LLMNodeAssertions extends Assertions {

	public static LLMNodeOptionsAssert assertThat(LLMNodeOptions actual) {
		return new LLMNodeOptionsAssert(actual);
	}
}