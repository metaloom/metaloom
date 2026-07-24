package io.metaloom.cortex.node.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class LLMNodeOptions extends AbstractNodeOptions<LLMNodeOptions> {

	private String ollamaUrl = "http://127.0.0.1:11434";

	/** The LLM backend protocol. Defaults to Ollama; set to {@code VLLM} for an OpenAI-compatible endpoint. */
	private LLMProviderType providerType = LLMProviderType.OLLAMA;

	private Map<String, LLMNodePrompt> prompts = new HashMap<>();

	public String ollamaUrl() {
		return ollamaUrl;
	}

	public void setOllamaUrl(String ollamaUrl) {
		this.ollamaUrl = ollamaUrl;
	}

	public LLMProviderType providerType() {
		return providerType;
	}

	public LLMNodeOptions setProviderType(LLMProviderType providerType) {
		this.providerType = providerType;
		return this;
	}

	public Map<String, LLMNodePrompt> getPrompts() {
		return prompts;
	}

	public void setPrompts(Map<String, LLMNodePrompt> prompts) {
		this.prompts = prompts;
	}

	@Override
	protected LLMNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// ollamaUrl must not be empty
		if (ollamaUrl == null || ollamaUrl.isBlank()) {
			errors.add("ollamaUrl must not be empty");
		}
		
		// prompts must not be empty
		if (prompts == null || prompts.isEmpty()) {
			errors.add("prompts must not be empty");
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
