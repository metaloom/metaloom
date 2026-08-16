package io.metaloom.loom.api.options;

/**
 * Options which control the chat agent (LLM provider, model, agentic loop limits).
 */
public class AiOptions implements Option {

	/** Where llama.cpp's {@code llama-server} serves the OpenAI-compatible API by default. */
	public static final String DEFAULT_URL = "http://127.0.0.1:8080/v1";

	public static final String DEFAULT_MODEL_ID = "openai/gpt-oss-20b";

	public static final int DEFAULT_CONTEXT_WINDOW = 16384;

	public static final int DEFAULT_MAX_TURNS = 8;

	public static final int DEFAULT_TOOL_TIMEOUT_MS = 30_000;

	/** Tokens held back from {@link #contextWindow} so the model always has room to answer. */
	public static final int DEFAULT_CONTEXT_RESERVE_TOKENS = 2048;

	/** {@code 0} = the replayed transcript is bounded by the context budget alone. */
	public static final int DEFAULT_HISTORY_MAX_MESSAGES = 0;

	/** Messages past the summary watermark that trigger a compaction pass. */
	public static final int DEFAULT_COMPACTION_THRESHOLD_MESSAGES = 20;

	/** Upper bound on the stored conversation summary. */
	public static final int DEFAULT_COMPACTION_MAX_CHARS = 4096;

	@EnvironmentVariable(name = "LOOM_AI_ENABLED", description = "Override the flag which enables the chat agent.")
	private boolean enabled = true;

	@EnvironmentVariable(name = "LOOM_AI_URL", description = "Override the URL of the OpenAI-compatible LLM server.")
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

	@EnvironmentVariable(name = "LOOM_AI_STREAMING", description = "Override the flag which enables true token/reasoning streaming (requires a backend that streams tool calls; falls back to turn-granular streaming when disabled).")
	private boolean streaming = false;

	@EnvironmentVariable(name = "LOOM_AI_TITLE_GENERATION", description = "Override the flag which enables automatic chat title generation.")
	private boolean titleGeneration = true;

	@EnvironmentVariable(name = "LOOM_AI_CONTEXT_RESERVE_TOKENS", description = "Override the amount of context window tokens reserved for the model's completion. The transcript is budgeted against the window minus this reserve.")
	private int contextReserveTokens = DEFAULT_CONTEXT_RESERVE_TOKENS;

	@EnvironmentVariable(name = "LOOM_AI_HISTORY_MAX_MESSAGES", description = "Override the hard ceiling on the number of persisted messages replayed into the LLM history. 0 means the context budget is the only limit.")
	private int historyMaxMessages = DEFAULT_HISTORY_MAX_MESSAGES;

	@EnvironmentVariable(name = "LOOM_AI_COMPACTION_THRESHOLD_MESSAGES", description = "Override how many messages past the stored summary watermark trigger a rolling conversation compaction.")
	private int compactionThresholdMessages = DEFAULT_COMPACTION_THRESHOLD_MESSAGES;

	@EnvironmentVariable(name = "LOOM_AI_COMPACTION_MAX_CHARS", description = "Override the maximum length of the rolling conversation summary stored on the chat.")
	private int compactionMaxChars = DEFAULT_COMPACTION_MAX_CHARS;

	public boolean isEnabled() {
		return enabled;
	}

	public AiOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
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

	public int getContextReserveTokens() {
		return contextReserveTokens;
	}

	public AiOptions setContextReserveTokens(int contextReserveTokens) {
		this.contextReserveTokens = contextReserveTokens;
		return this;
	}

	public int getHistoryMaxMessages() {
		return historyMaxMessages;
	}

	public AiOptions setHistoryMaxMessages(int historyMaxMessages) {
		this.historyMaxMessages = historyMaxMessages;
		return this;
	}

	public int getCompactionThresholdMessages() {
		return compactionThresholdMessages;
	}

	public AiOptions setCompactionThresholdMessages(int compactionThresholdMessages) {
		this.compactionThresholdMessages = compactionThresholdMessages;
		return this;
	}

	public int getCompactionMaxChars() {
		return compactionMaxChars;
	}

	public AiOptions setCompactionMaxChars(int compactionMaxChars) {
		this.compactionMaxChars = compactionMaxChars;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		errors.notBlank("url", url);
		errors.notBlank("modelId", modelId);
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnvBoolean("LOOM_AI_ENABLED", this::setEnabled);
		OptionUtils.applyEnv("LOOM_AI_URL", this::setUrl);
		OptionUtils.applyEnv("LOOM_AI_MODEL_ID", this::setModelId);
		OptionUtils.applyEnvInt("LOOM_AI_CONTEXT_WINDOW", this::setContextWindow);
		OptionUtils.applyEnvInt("LOOM_AI_MAX_TURNS", this::setMaxTurns);
		OptionUtils.applyEnvInt("LOOM_AI_TOOL_TIMEOUT_MS", this::setToolTimeoutMs);
		OptionUtils.applyEnvBoolean("LOOM_AI_THINK_ENABLED", this::setThinkEnabled);
		OptionUtils.applyEnvBoolean("LOOM_AI_STREAMING", this::setStreaming);
		OptionUtils.applyEnvBoolean("LOOM_AI_TITLE_GENERATION", this::setTitleGeneration);
		OptionUtils.applyEnvInt("LOOM_AI_CONTEXT_RESERVE_TOKENS", this::setContextReserveTokens);
		OptionUtils.applyEnvInt("LOOM_AI_HISTORY_MAX_MESSAGES", this::setHistoryMaxMessages);
		OptionUtils.applyEnvInt("LOOM_AI_COMPACTION_THRESHOLD_MESSAGES", this::setCompactionThresholdMessages);
		OptionUtils.applyEnvInt("LOOM_AI_COMPACTION_MAX_CHARS", this::setCompactionMaxChars);
	}
}
