package io.metaloom.loom.api.options;

/**
 * Options which control the chat agent (LLM provider, model, agentic loop limits).
 */
public class AiOptions implements Option {

	public static final String DEFAULT_PROVIDER_TYPE = "OLLAMA";

	public static final String DEFAULT_URL = "http://127.0.0.1:11434";

	public static final String DEFAULT_MODEL_ID = "gpt-oss:20b";

	public static final int DEFAULT_CONTEXT_WINDOW = 16384;

	public static final int DEFAULT_MAX_TURNS = 8;

	public static final int DEFAULT_TOOL_TIMEOUT_MS = 30_000;

	@EnvironmentVariable(name = "LOOM_AI_ENABLED", description = "Override the flag which enables the chat agent.")
	private boolean enabled = true;

	@EnvironmentVariable(name = "LOOM_AI_PROVIDER_TYPE", description = "Override the LLM provider type (OLLAMA, VLLM).")
	private String providerType = DEFAULT_PROVIDER_TYPE;

	@EnvironmentVariable(name = "LOOM_AI_URL", description = "Override the LLM provider server url.")
	private String url = DEFAULT_URL;

	@EnvironmentVariable(name = "LOOM_AI_MODEL_ID", description = "Override the model id used by the chat agent.")
	private String modelId = DEFAULT_MODEL_ID;

	@EnvironmentVariable(name = "LOOM_AI_CONTEXT_WINDOW", description = "Override the context window size used by the chat agent.")
	private int contextWindow = DEFAULT_CONTEXT_WINDOW;

	@EnvironmentVariable(name = "LOOM_AI_MAX_TURNS", description = "Override the maximum amount of agentic loop turns per chat message.")
	private int maxTurns = DEFAULT_MAX_TURNS;

	@EnvironmentVariable(name = "LOOM_AI_TOOL_TIMEOUT_MS", description = "Override the timeout for a single tool invocation in milliseconds.")
	private int toolTimeoutMs = DEFAULT_TOOL_TIMEOUT_MS;

	@EnvironmentVariable(name = "LOOM_AI_THINK_ENABLED", description = "Override the flag which enables reasoning/think mode for the chat agent.")
	private boolean thinkEnabled = true;

	@EnvironmentVariable(name = "LOOM_AI_STREAMING", description = "Override the flag which enables true token/reasoning streaming (requires provider support; falls back to turn-granular streaming when disabled).")
	private boolean streaming = false;

	@EnvironmentVariable(name = "LOOM_AI_TITLE_GENERATION", description = "Override the flag which enables automatic chat title generation.")
	private boolean titleGeneration = true;

	public boolean isEnabled() {
		return enabled;
	}

	public AiOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public String getProviderType() {
		return providerType;
	}

	public AiOptions setProviderType(String providerType) {
		this.providerType = providerType;
		return this;
	}

	public String getUrl() {
		return url;
	}

	public AiOptions setUrl(String url) {
		this.url = url;
		return this;
	}

	public String getModelId() {
		return modelId;
	}

	public AiOptions setModelId(String modelId) {
		this.modelId = modelId;
		return this;
	}

	public int getContextWindow() {
		return contextWindow;
	}

	public AiOptions setContextWindow(int contextWindow) {
		this.contextWindow = contextWindow;
		return this;
	}

	public int getMaxTurns() {
		return maxTurns;
	}

	public AiOptions setMaxTurns(int maxTurns) {
		this.maxTurns = maxTurns;
		return this;
	}

	public int getToolTimeoutMs() {
		return toolTimeoutMs;
	}

	public AiOptions setToolTimeoutMs(int toolTimeoutMs) {
		this.toolTimeoutMs = toolTimeoutMs;
		return this;
	}

	public boolean isThinkEnabled() {
		return thinkEnabled;
	}

	public AiOptions setThinkEnabled(boolean thinkEnabled) {
		this.thinkEnabled = thinkEnabled;
		return this;
	}

	public boolean isStreaming() {
		return streaming;
	}

	public AiOptions setStreaming(boolean streaming) {
		this.streaming = streaming;
		return this;
	}

	public boolean isTitleGeneration() {
		return titleGeneration;
	}

	public AiOptions setTitleGeneration(boolean titleGeneration) {
		this.titleGeneration = titleGeneration;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		errors.notBlank("providerType", providerType);
		errors.notBlank("url", url);
		errors.notBlank("modelId", modelId);
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnvBoolean("LOOM_AI_ENABLED", this::setEnabled);
		OptionUtils.applyEnv("LOOM_AI_PROVIDER_TYPE", this::setProviderType);
		OptionUtils.applyEnv("LOOM_AI_URL", this::setUrl);
		OptionUtils.applyEnv("LOOM_AI_MODEL_ID", this::setModelId);
		OptionUtils.applyEnvInt("LOOM_AI_CONTEXT_WINDOW", this::setContextWindow);
		OptionUtils.applyEnvInt("LOOM_AI_MAX_TURNS", this::setMaxTurns);
		OptionUtils.applyEnvInt("LOOM_AI_TOOL_TIMEOUT_MS", this::setToolTimeoutMs);
		OptionUtils.applyEnvBoolean("LOOM_AI_THINK_ENABLED", this::setThinkEnabled);
		OptionUtils.applyEnvBoolean("LOOM_AI_STREAMING", this::setStreaming);
		OptionUtils.applyEnvBoolean("LOOM_AI_TITLE_GENERATION", this::setTitleGeneration);
	}
}
