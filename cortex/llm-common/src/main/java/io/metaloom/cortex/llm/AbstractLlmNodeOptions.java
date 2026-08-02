package io.metaloom.cortex.llm;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

/**
 * The options every node that talks to a language model needs: where the backend is, what protocol
 * it speaks, and how large a request it will accept.
 *
 * <p>
 * The accessor names are the ones {@code LLMNodeOptions} has always used ({@code ollamaUrl()},
 * {@code providerType()}) rather than the {@code getX} convention. That is on purpose: the worker
 * YAML key and every existing test and assertj helper are written against them, and renaming here
 * would be a breaking change to a configuration file for no gain.
 * </p>
 *
 * @param <T> the concrete options type, for the fluent setters inherited from {@link AbstractNodeOptions}
 */
public abstract class AbstractLlmNodeOptions<T extends AbstractLlmNodeOptions<T>> extends AbstractNodeOptions<T> {

	public static final String DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434";

	/** Matches the value the llm node hardcoded at its call site before this was configurable. */
	public static final int DEFAULT_CONTEXT_WINDOW = 2048;

	private String ollamaUrl = DEFAULT_OLLAMA_URL;

	/** The LLM backend protocol. Defaults to Ollama; set to {@code VLLM} for an OpenAI-compatible endpoint. */
	private LLMProviderType providerType = LLMProviderType.OLLAMA;

	private int contextWindow = DEFAULT_CONTEXT_WINDOW;

	public String ollamaUrl() {
		return ollamaUrl;
	}

	public void setOllamaUrl(String ollamaUrl) {
		this.ollamaUrl = ollamaUrl;
	}

	public LLMProviderType providerType() {
		return providerType;
	}

	public T setProviderType(LLMProviderType providerType) {
		this.providerType = providerType;
		return self();
	}

	public int getContextWindow() {
		return contextWindow;
	}

	public T setContextWindow(int contextWindow) {
		this.contextWindow = contextWindow;
		return self();
	}

	/**
	 * Validate the endpoint settings shared by every LLM-backed node. Subclasses add their own errors
	 * on top, exactly as they do with {@code validateCommon()}.
	 *
	 * @return list of validation errors, empty if valid
	 */
	protected List<String> validateEndpoint() {
		List<String> errors = new ArrayList<>();
		if (ollamaUrl == null || ollamaUrl.isBlank()) {
			errors.add("ollamaUrl must not be empty");
		}
		if (contextWindow < 1) {
			errors.add("contextWindow must be at least 1, got " + contextWindow);
		}
		return errors;
	}
}
