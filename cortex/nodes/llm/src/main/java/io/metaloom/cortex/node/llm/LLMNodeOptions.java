package io.metaloom.cortex.node.llm;

import java.util.HashMap;
import java.util.Map;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class LLMNodeOptions extends AbstractNodeOptions<LLMNodeOptions> {

	private String ollamaUrl = "http://127.0.0.1:11434";

	private Map<String, LLMNodePrompt> prompts = new HashMap<>();

	public String ollamaUrl() {
		return ollamaUrl;
	}

	public void setOllamaUrl(String ollamaUrl) {
		this.ollamaUrl = ollamaUrl;
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
}
