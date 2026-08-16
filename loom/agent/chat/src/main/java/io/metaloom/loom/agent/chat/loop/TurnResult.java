package io.metaloom.loom.agent.chat.loop;

import java.util.List;

import io.metaloom.ai.genai.llm.TokenUsage;
import io.metaloom.ai.genai.llm.ToolCall;

/**
 * Outcome of a single LLM turn.
 *
 * @param text
 *            Text content of the turn (may be null)
 * @param reasoning
 *            Accumulated reasoning/thinking content of the turn (may be null)
 * @param toolCalls
 *            Tool calls requested by the model (never null, may be empty)
 * @param usage
 *            Token accounting the server reported for this turn (never null). This is the only <em>measured</em> number the loop ever sees —
 *            {@link ContextBudget} can merely estimate — so it is what {@code turn_end} reports and what the chat's estimator calibration is derived from.
 *            {@link TokenUsage#NONE} when the server reported nothing, which {@link TokenUsage#isReported()} distinguishes from a genuine zero.
 */
public record TurnResult(String text, String reasoning, List<ToolCall> toolCalls, TokenUsage usage) {

	public TurnResult {
		toolCalls = toolCalls == null ? List.of() : toolCalls;
		usage = usage == null ? TokenUsage.NONE : usage;
	}

	/**
	 * A turn for which no token accounting is available.
	 */
	public TurnResult(String text, String reasoning, List<ToolCall> toolCalls) {
		this(text, reasoning, toolCalls, TokenUsage.NONE);
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}

}
