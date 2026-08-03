package io.metaloom.cortex.llm;

/**
 * Where a node's model answers from, and how much of it fits in one request.
 *
 * <p>
 * Deliberately not part of the Dagger graph. {@link LLMProviderModule} binds the <em>protocol</em>
 * once per worker; the endpoint is a per-node-instance decision read off that node's own options, so
 * one worker can drive several backends at the same time without a second binding.
 * </p>
 *
 * @param url            base URL of the OpenAI-compatible backend, e.g. {@code http://127.0.0.1:8080/v1}
 * @param contextWindow  tokens the model is told it may use for one call
 */
public record LlmEndpoint(String url, int contextWindow) {

	public static LlmEndpoint of(AbstractLlmNodeOptions<?> options) {
		return new LlmEndpoint(options.openaiUrl(), options.getContextWindow());
	}
}
