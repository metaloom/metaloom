package io.metaloom.cortex.node.vlm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@code vlm} node.
 *
 * <p>
 * One endpoint serves every prompt: {@link #getEndpointUrl()} points at an OpenAI-compatible server (vLLM, llama.cpp, ...) and each entry in
 * {@link #getPrompts()} selects its own model id on that server.
 * </p>
 */
public class VlmNodeOptions extends AbstractNodeOptions<VlmNodeOptions> {

	public static final String KEY = "vlm";

	/** Where an OpenAI-compatible vision endpoint is expected by default (the port vLLM serves on). */
	public static final String DEFAULT_ENDPOINT_URL = "http://127.0.0.1:8000";

	@ParamDoc(label = "Endpoint URL", description = "Base URL of the OpenAI-compatible vision endpoint (e.g. vLLM)")
	private String endpointUrl = DEFAULT_ENDPOINT_URL;

	/** Bearer token for the endpoint. Usually unset - a local vLLM does not check it. */
	@ParamDoc(label = "API Key", description = "Bearer token for the endpoint. Usually unset - a local vLLM does not check it")
	private String apiKey;

	/**
	 * Prompt id -&gt; task. Left empty the node falls back to the olmOCR preset.
	 *
	 * <p>
	 * The prompts drive the node's dynamic {@code result_<promptId>} ports. The contract advertises this
	 * field because it is the only thing that actually configures them: the four per-prompt knobs the
	 * descriptor used to expose at node level ({@code model}, {@code responseFormat}, {@code maxImageDim},
	 * {@code maxTokens}) are fields of {@link VlmNodePrompt}, so a value set against the node was bound
	 * to nothing and silently discarded.
	 * </p>
	 */
	@ParamDoc(label = "Prompts", rows = 8,
		description = "Prompt id to task, as {\"<id>\": {\"model\": ..., \"prompt\": ..., \"responseFormat\": "
			+ "TEXT|JSON|OLMOCR, \"maxImageDim\": ..., \"maxTokens\": ...}}. Each entry adds a "
			+ "result_<id> output port. Left empty the node falls back to the olmOCR preset")
	private Map<String, VlmNodePrompt> prompts = new LinkedHashMap<>();

	@Override
	protected VlmNodeOptions self() {
		return this;
	}

	public String getEndpointUrl() {
		return endpointUrl;
	}

	public VlmNodeOptions setEndpointUrl(String endpointUrl) {
		this.endpointUrl = endpointUrl;
		return self();
	}

	public String getApiKey() {
		return apiKey;
	}

	public VlmNodeOptions setApiKey(String apiKey) {
		this.apiKey = apiKey;
		return self();
	}

	public Map<String, VlmNodePrompt> getPrompts() {
		return prompts;
	}

	public VlmNodeOptions setPrompts(Map<String, VlmNodePrompt> prompts) {
		this.prompts = prompts;
		return self();
	}

	/**
	 * Register a single prompt under the given id.
	 */
	public VlmNodeOptions addPrompt(String id, VlmNodePrompt prompt) {
		this.prompts.put(id, prompt);
		return self();
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		if (endpointUrl == null || endpointUrl.isBlank()) {
			errors.add("endpointUrl must not be empty");
		}

		if (prompts == null || prompts.isEmpty()) {
			errors.add("prompts must not be empty");
		} else {
			for (Map.Entry<String, VlmNodePrompt> entry : prompts.entrySet()) {
				String id = entry.getKey();
				VlmNodePrompt prompt = entry.getValue();
				if (prompt == null) {
					errors.add("prompt '" + id + "' must not be null");
					continue;
				}
				if (prompt.getModel() == null || prompt.getModel().isBlank()) {
					errors.add("prompt '" + id + "' must define a model");
				}
				if (prompt.getPrompt() == null || prompt.getPrompt().isBlank()) {
					errors.add("prompt '" + id + "' must define a prompt text");
				}
				if (prompt.getMaxTokens() <= 0) {
					errors.add("prompt '" + id + "' maxTokens must be positive, got " + prompt.getMaxTokens());
				}
				if (prompt.getMaxImageDim() < 0) {
					errors.add("prompt '" + id + "' maxImageDim must be non-negative, got " + prompt.getMaxImageDim());
				}
				if (prompt.getTemperature() < 0) {
					errors.add("prompt '" + id + "' temperature must be non-negative, got " + prompt.getTemperature());
				}
			}
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
