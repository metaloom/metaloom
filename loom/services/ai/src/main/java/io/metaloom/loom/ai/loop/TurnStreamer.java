package io.metaloom.loom.ai.loop;

import io.metaloom.ai.genai.llm.LLMContext;

/**
 * Abstraction over a single LLM turn of the agentic loop.
 *
 * <p>The {@code BlockingTurnStreamer} wraps the blocking {@code LLMProvider.generateWithTools} call and emits the whole turn text as one delta
 * (turn-granular streaming). Once genai-utils supports streaming with tools a {@code StreamingTurnStreamer} can be swapped in without touching the loop —
 * it will relay real token and reasoning deltas through the {@link TurnListener}.</p>
 */
public interface TurnStreamer {

	/**
	 * Run one LLM turn. Implementations relay text/reasoning deltas to the listener while the turn is in flight and return the complete turn outcome.
	 *
	 * @param ctx
	 *            LLM context (history, model, tools)
	 * @param listener
	 *            Receiver for live deltas
	 * @return The completed turn
	 */
	TurnResult streamTurn(LLMContext ctx, TurnListener listener);

	/**
	 * Run a small auxiliary completion without tools or streaming (e.g. chat title generation). Implementations may return null to signal that the capability
	 * is unavailable — callers must treat that as "skip".
	 */
	default String completeText(LLMContext ctx) {
		return null;
	}

}
