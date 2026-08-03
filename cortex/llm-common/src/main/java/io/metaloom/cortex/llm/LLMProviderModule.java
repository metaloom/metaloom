package io.metaloom.cortex.llm;

import dagger.Module;
import dagger.Provides;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.openai.OpenAILLMProvider;

/**
 * The single {@link LLMProvider} binding, shared by every node that talks to a language model.
 *
 * <p>
 * This used to sit in {@code LLMNodeModule}, which was fine while exactly one node used a model.
 * Dagger rejects a duplicate binding outright, so the second such node could not simply repeat the
 * {@code @Provides} — the whole component stopped compiling. Both modules now {@code include} this
 * one; Dagger deduplicates a module included from several places, so that costs nothing.
 * </p>
 *
 * <p>
 * The provider is protocol-level only. <em>Which</em> model answers, and at what URL, is a
 * per-instance decision carried on the node's own options and passed as a
 * {@code LargeLanguageModel} at call time — so several OpenAI-compatible endpoints can coexist in
 * one worker without a second binding.
 * </p>
 */
@Module
public abstract class LLMProviderModule {

	@Provides
	public static LLMProvider llmProvider() {
		return new OpenAILLMProvider();
	}
}
