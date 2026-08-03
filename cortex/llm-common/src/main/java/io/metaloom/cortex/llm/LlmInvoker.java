package io.metaloom.cortex.llm;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.llm.impl.LargeLanguageModelImpl;
import io.metaloom.ai.genai.llm.prompt.Prompt;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.vertx.core.json.JsonObject;

/**
 * One model call, with the metrics around it.
 *
 * <p>
 * Every node that asks a language model something needs the same four lines — build a
 * {@link LargeLanguageModel} from its endpoint options, wrap the prompt in an {@link LLMContext},
 * call the provider, and report how long it took and whether it worked. Reporting the failure was
 * the part that kept getting dropped when it was copied: a node that only calls
 * {@code recordAiCall} on the happy path makes a broken backend look like an idle one on the
 * dashboard.
 * </p>
 *
 * <p>
 * The provider is the injected protocol implementation; the model id and endpoint are per-call, so
 * a node with several configured prompts can point each at a different model.
 * </p>
 */
public class LlmInvoker {

	private final LLMProvider provider;

	private final LlmEndpoint endpoint;

	public LlmInvoker(LLMProvider provider, LlmEndpoint endpoint) {
		this.provider = provider;
		this.endpoint = endpoint;
	}

	/**
	 * Ask the model and return its answer as plain text.
	 *
	 * @param model        model id, e.g. {@code Qwen/Qwen3-8B}
	 * @param prompt       the prompt, with its parameters already set
	 * @param metrics      sink for the call timing
	 * @param metricsLabel provider label the timing is recorded under
	 * @return the model's answer
	 */
	public String generate(String model, Prompt prompt, CortexMetrics metrics, String metricsLabel) {
		return call(model, prompt, metrics, metricsLabel, provider::generate);
	}

	/**
	 * Ask the model and return its answer parsed as JSON.
	 *
	 * @param model        model id
	 * @param prompt       the prompt, with its parameters already set
	 * @param metrics      sink for the call timing
	 * @param metricsLabel provider label the timing is recorded under
	 * @return the model's answer as a JSON object
	 */
	public JsonObject generateJson(String model, Prompt prompt, CortexMetrics metrics, String metricsLabel) {
		return call(model, prompt, metrics, metricsLabel, provider::generateJson);
	}

	private <R> R call(String model, Prompt prompt, CortexMetrics metrics, String metricsLabel, ProviderCall<R> call) {
		LargeLanguageModel llm = new LargeLanguageModelImpl(model, endpoint.url(), endpoint.contextWindow());
		LLMContext ctx = LLMContext.ctx(prompt, llm);

		long start = System.currentTimeMillis();
		try {
			R result = call.apply(ctx);
			metrics.recordAiCall(metricsLabel, true, System.currentTimeMillis() - start);
			return result;
		} catch (RuntimeException e) {
			metrics.recordAiCall(metricsLabel, false, System.currentTimeMillis() - start);
			throw e;
		}
	}

	@FunctionalInterface
	private interface ProviderCall<R> {
		R apply(LLMContext ctx);
	}
}
