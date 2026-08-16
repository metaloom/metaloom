package io.metaloom.loom.agent.chat.loop;

import java.util.List;

import io.metaloom.ai.genai.llm.ChatMessage;
import io.metaloom.ai.genai.llm.ToolCall;
import io.metaloom.ai.genai.llm.ToolDefinition;
import io.metaloom.ai.genai.llm.TokenUsage;

/**
 * The context window budget of one agent run (CHAT_TASKS CTX1).
 *
 * <h2>Why this estimates rather than tokenizes</h2>
 *
 * <p>
 * Eviction decisions have to be made <em>before</em> the request is sent, and the only authoritative token count — the {@code usage} object the server
 * attaches to its response ({@link TokenUsage}) — only exists <em>afterwards</em>. So the pre-flight number is necessarily an estimate.
 * </p>
 *
 * <p>
 * The estimate is a deliberately crude <b>{@value #CHARS_PER_TOKEN} characters per token</b> heuristic plus a small per-message envelope allowance. It is
 * <em>not</em> a tokenizer and must never be presented as an exact count: for English prose it lands within roughly ±25% of a BPE tokenizer, and for
 * dense JSON tool schemas it under-counts. That accuracy is all this number has to have — it drives which whole exchanges survive
 * {@link ConversationHistory} assembly, and the reserve exists to absorb the error.
 * </p>
 *
 * <h2>Calibration against measured usage</h2>
 *
 * <p>
 * Because the model server does report real prompt tokens, the guess does not have to stay a guess for the whole life of a chat.
 * {@link #calibrationFrom(long, int, double)} turns one measured turn into a correction factor which is persisted on the chat and fed back into the next
 * run's budget, so a chat converges on its own model's tokenizer instead of trusting chars/4 forever. The factor is clamped to
 * [{@value #MIN_CALIBRATION}, {@value #MAX_CALIBRATION}] so a single odd measurement — a cache hit, a server that reports cumulative counts, a turn whose
 * prompt was dominated by an image — cannot wedge the budget at an absurd value.
 * </p>
 *
 * <p>
 * Instances are immutable and side-effect free.
 * </p>
 */
public class ContextBudget {

	/**
	 * The heuristic divisor. Four characters per token is the widely used rule of thumb for English text under a byte-pair encoding.
	 */
	public static final int CHARS_PER_TOKEN = 4;

	/**
	 * Tokens charged per message on top of its text, covering the role marker and the chat-template delimiters the server wraps around every message.
	 */
	public static final int MESSAGE_OVERHEAD_TOKENS = 4;

	/**
	 * Tokens charged per tool call on top of its serialized arguments, covering the call id and the function-call envelope.
	 */
	public static final int TOOL_CALL_OVERHEAD_TOKENS = 8;

	/** Tokens charged per tool definition on top of its name, description and JSON schema. */
	public static final int TOOL_DEFINITION_OVERHEAD_TOKENS = 8;

	public static final double MIN_CALIBRATION = 0.5d;

	public static final double MAX_CALIBRATION = 3.0d;

	private final int limit;

	private final int reserve;

	private final double calibration;

	/**
	 * @param limit
	 *            The full context window reported to the provider ({@code LOOM_AI_CONTEXT_WINDOW}).
	 * @param reserve
	 *            Tokens held back for the completion ({@code LOOM_AI_CONTEXT_RESERVE_TOKENS}).
	 * @param calibration
	 *            Correction factor applied to every estimate, {@code 1.0} for an uncalibrated chat. Clamped on construction.
	 */
	public ContextBudget(int limit, int reserve, double calibration) {
		this.limit = Math.max(0, limit);
		this.reserve = Math.max(0, Math.min(reserve, this.limit));
		this.calibration = clampCalibration(calibration);
	}

	/**
	 * The full context window.
	 */
	public int limit() {
		return limit;
	}

	/**
	 * Tokens reserved for the model's completion — never available to the prompt.
	 */
	public int reserve() {
		return reserve;
	}

	/**
	 * The largest prompt this run may assemble: {@link #limit()} minus {@link #reserve()}.
	 */
	public int available() {
		return limit - reserve;
	}

	/**
	 * Tokens still free once {@code used} have been spent. Never negative.
	 */
	public int remaining(int used) {
		return Math.max(0, available() - used);
	}

	/**
	 * The calibration factor in force, {@code 1.0} when the chat has never seen a measured turn.
	 */
	public double calibration() {
		return calibration;
	}

	/**
	 * Estimate the token cost of a piece of text. Null and blank are free.
	 */
	public int estimate(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return apply((text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
	}

	/**
	 * Estimate the token cost of a single message, including its envelope and any tool calls it carries.
	 */
	public int estimate(ChatMessage message) {
		if (message == null) {
			return 0;
		}
		int raw = MESSAGE_OVERHEAD_TOKENS + rawChars(message.getText());
		List<ToolCall> calls = message.getToolCalls();
		if (calls != null) {
			for (ToolCall call : calls) {
				raw += TOOL_CALL_OVERHEAD_TOKENS + rawChars(call.name());
				if (call.arguments() != null) {
					raw += rawChars(call.arguments().encode());
				}
			}
		}
		return apply(raw);
	}

	/**
	 * Estimate the token cost of a whole message list.
	 */
	public int estimate(List<ChatMessage> messages) {
		if (messages == null) {
			return 0;
		}
		int total = 0;
		for (ChatMessage message : messages) {
			total += estimate(message);
		}
		return total;
	}

	/**
	 * Estimate the token cost of the advertised tool schemas — the part of the static prefix that is paid on every single turn.
	 */
	public int estimateTools(List<ToolDefinition> tools) {
		if (tools == null) {
			return 0;
		}
		int raw = 0;
		for (ToolDefinition tool : tools) {
			raw += TOOL_DEFINITION_OVERHEAD_TOKENS + rawChars(tool.name()) + rawChars(tool.description());
			if (tool.parameters() != null) {
				raw += rawChars(tool.parameters().encode());
			}
		}
		return apply(raw);
	}

	/**
	 * Derive a new calibration factor from one measured turn.
	 *
	 * <p>
	 * Returns {@code previous} unchanged when the server reported nothing, when the estimate was zero, or when the measurement is unusable — a caller
	 * should always be able to assign the result without checking.
	 * </p>
	 *
	 * @param measuredPromptTokens
	 *            {@link TokenUsage#promptTokens()} of a completed turn
	 * @param estimatedPromptTokens
	 *            what this budget estimated for the very same prompt
	 * @param previous
	 *            the factor currently in force
	 * @return the factor to persist, always within [{@value #MIN_CALIBRATION}, {@value #MAX_CALIBRATION}]
	 */
	public static double calibrationFrom(long measuredPromptTokens, int estimatedPromptTokens, double previous) {
		if (measuredPromptTokens <= 0 || estimatedPromptTokens <= 0) {
			return clampCalibration(previous);
		}
		// The estimate already had `previous` folded in, so divide it back out to recover the ratio
		// against the raw chars/4 heuristic — otherwise the factor would compound run over run.
		double raw = estimatedPromptTokens / clampCalibration(previous);
		if (raw <= 0) {
			return clampCalibration(previous);
		}
		return clampCalibration(measuredPromptTokens / raw);
	}

	private static double clampCalibration(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
			return 1.0d;
		}
		return Math.max(MIN_CALIBRATION, Math.min(MAX_CALIBRATION, value));
	}

	private int apply(int rawTokens) {
		return (int) Math.ceil(rawTokens * calibration);
	}

	private static int rawChars(String text) {
		return text == null ? 0 : (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
	}

	@Override
	public String toString() {
		return "ContextBudget[limit=" + limit + ",reserve=" + reserve + ",calibration=" + calibration + "]";
	}
}
