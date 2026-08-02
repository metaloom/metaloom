package io.metaloom.cortex.llm;

import io.metaloom.ai.genai.llm.LLMProviderType;

/**
 * Where a node's model answers from, and how much of it fits in one request.
 *
 * <p>
 * Deliberately not part of the Dagger graph. {@link LLMProviderModule} binds the <em>protocol</em>
 * once per worker; the endpoint is a per-node-instance decision read off that node's own options, so
 * one worker can drive an Ollama and a vLLM backend at the same time without a second binding.
 * </p>
 *
 * @param url            base URL of the backend, e.g. {@code http://127.0.0.1:11434}
 * @param providerType   the wire protocol spoken at that URL
 * @param contextWindow  tokens the model is told it may use for one call
 */
public record LlmEndpoint(String url, LLMProviderType providerType, int contextWindow) {

	public static LlmEndpoint of(AbstractLlmNodeOptions<?> options) {
		return new LlmEndpoint(options.ollamaUrl(), options.providerType(), options.getContextWindow());
	}
}
