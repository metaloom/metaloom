package io.metaloom.loom.agent.chat.loop;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.ToolCallResponse;

/**
 * Turn streamer backed by the blocking {@code generateWithTools} call. The whole turn text arrives as a single delta — tool events still stream live, so the
 * UX remains turn-granular until true token streaming lands in genai-utils.
 */
public class BlockingTurnStreamer implements TurnStreamer {

	private final LLMProvider provider;

	public BlockingTurnStreamer(LLMProvider provider) {
		this.provider = provider;
	}

	@Override
	public TurnResult streamTurn(LLMContext ctx, TurnListener listener) {
		ToolCallResponse response = provider.generateWithTools(ctx);
		String text = response.content();
		if (text != null && !text.isBlank()) {
			listener.onTextDelta(text);
		}
		return new TurnResult(text, null, response.toolCalls());
	}

	@Override
	public String completeText(LLMContext ctx) {
		return provider.generate(ctx);
	}

}
