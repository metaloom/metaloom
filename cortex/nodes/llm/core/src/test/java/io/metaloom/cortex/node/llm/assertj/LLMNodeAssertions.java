package io.metaloom.cortex.node.llm.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.llm.LLMNodeOptions;

/**
 * Entry point for LLM node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all an LLM
 * test needs — it exposes the LLM assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.llm.assertj.LLMNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasOllamaUrl("http://localhost:11434").hasPromptCount(2);
 * </pre>
 */
public class LLMNodeAssertions extends NodeAssertions {

	public static LLMNodeOptionsAssert assertThat(LLMNodeOptions actual) {
		return new LLMNodeOptionsAssert(actual);
	}
}
