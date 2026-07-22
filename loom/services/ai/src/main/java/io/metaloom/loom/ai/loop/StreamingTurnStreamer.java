package io.metaloom.loom.ai.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.StreamEvent;
import io.metaloom.ai.genai.llm.ToolCall;

/**
 * Turn streamer backed by {@code generateStreamWithTools} — relays real token and reasoning deltas to the listener as they arrive from the provider.
 * Selected via {@code LOOM_AI_STREAMING=true}; requires provider support (Ollama), otherwise the provider throws and the run fails terminally — use the
 * {@link BlockingTurnStreamer} fallback in that case.
 */
public class StreamingTurnStreamer implements TurnStreamer {

	private final LLMProvider provider;

	public StreamingTurnStreamer(LLMProvider provider) {
		this.provider = provider;
	}

	@Override
	public TurnResult streamTurn(LLMContext ctx, TurnListener listener) {
		StringBuilder text = new StringBuilder();
		StringBuilder reasoning = new StringBuilder();
		List<ToolCall> toolCalls = new ArrayList<>();
		AtomicReference<String> fullText = new AtomicReference<>();

		provider.generateStreamWithTools(ctx).blockingForEach(event -> {
			switch (event) {
			case StreamEvent.TextDelta delta -> {
				text.append(delta.text());
				listener.onTextDelta(delta.text());
			}
			case StreamEvent.ReasoningDelta delta -> {
				reasoning.append(delta.text());
				listener.onReasoningDelta(delta.text());
			}
			case StreamEvent.ToolCallsComplete calls -> toolCalls.addAll(calls.toolCalls());
			case StreamEvent.Completed completed -> fullText.set(completed.fullText());
			}
		});

		String finalText = fullText.get() != null ? fullText.get() : text.toString();
		return new TurnResult(finalText, reasoning.isEmpty() ? null : reasoning.toString(), toolCalls);
	}

	@Override
	public String completeText(LLMContext ctx) {
		return provider.generate(ctx);
	}

}
