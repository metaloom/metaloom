package io.metaloom.cortex.llm;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

/**
 * The options every node that talks to a language model needs: where the backend is and how large a
 * request it will accept.
 *
 * <p>
 * The backend is addressed by URL only. Everything supported speaks the OpenAI chat-completions
 * protocol — llama.cpp, vLLM, Ollama's {@code /v1} endpoint, OpenAI itself — so there is nothing
 * left to select between.
 * </p>
 *
 * <p>
 * The accessor names follow the {@code openaiUrl()} form rather than the {@code getX} convention,
 * matching the worker YAML key.
 * </p>
 *
 * @param <T> the concrete options type, for the fluent setters inherited from {@link AbstractNodeOptions}
 */
public abstract class AbstractLlmNodeOptions<T extends AbstractLlmNodeOptions<T>> extends AbstractNodeOptions<T> {

	/** Where llama.cpp's {@code llama-server} serves the OpenAI-compatible API by default. */
	public static final String DEFAULT_OPENAI_URL = "http://127.0.0.1:8080/v1";

	/** Matches the value the llm node hardcoded at its call site before this was configurable. */
	public static final int DEFAULT_CONTEXT_WINDOW = 2048;

	private String openaiUrl = DEFAULT_OPENAI_URL;

	private int contextWindow = DEFAULT_CONTEXT_WINDOW;

	public String openaiUrl() {
		return openaiUrl;
	}

	public void setOpenaiUrl(String openaiUrl) {
		this.openaiUrl = openaiUrl;
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
		if (openaiUrl == null || openaiUrl.isBlank()) {
			errors.add("openaiUrl must not be empty");
		}
		if (contextWindow < 1) {
			errors.add("contextWindow must be at least 1, got " + contextWindow);
		}
		return errors;
	}
}
