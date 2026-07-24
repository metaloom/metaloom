package io.metaloom.loom.agent.chat.loop;

/**
 * Receives live deltas of a single LLM turn.
 */
public interface TurnListener {

	void onTextDelta(String text);

	void onReasoningDelta(String text);

}
