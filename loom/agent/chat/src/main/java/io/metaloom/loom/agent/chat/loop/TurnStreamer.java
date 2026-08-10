package io.metaloom.loom.agent.chat.loop;

import io.metaloom.ai.genai.llm.LLMContext;

/**
 * Abstraction over a single LLM turn of the agentic loop.
 *
 * <p>The {@link BlockingTurnStreamer} wraps the blocking {@code LLMProvider.generateWithTools} call and emits the whole turn text as one delta
 * (turn-granular streaming). The {@link StreamingTurnStreamer} relays real token and reasoning deltas through the {@link TurnListener} as they arrive from
 * the provider; which one runs is decided by {@code LOOM_AI_STREAMING} and is invisible to the loop.</p>
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

	/**
	 * Interrupt the turn that is currently in flight, if any, so {@link #streamTurn(LLMContext, TurnListener)} returns without waiting for the provider to
	 * finish. Called from the aborting thread (the Vert.x event loop) while the turn blocks on a worker thread, so implementations must be thread-safe and
	 * must tolerate being called when no turn is running.
	 *
	 * <p>The default is a no-op: a turn that cannot be interrupted simply runs to completion and {@code AgentLoop}'s post-turn cancel check ends the run.</p>
	 */
	default void cancel() {
	}

}
