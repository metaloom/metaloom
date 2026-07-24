package io.metaloom.loom.agent.chat.loop;

import java.util.List;

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
 */
public record TurnResult(String text, String reasoning, List<ToolCall> toolCalls) {

	public TurnResult {
		toolCalls = toolCalls == null ? List.of() : toolCalls;
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}

}
