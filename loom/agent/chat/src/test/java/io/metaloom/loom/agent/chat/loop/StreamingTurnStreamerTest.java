package io.metaloom.loom.agent.chat.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.StreamEvent;
import io.metaloom.ai.genai.llm.ToolCall;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.json.JsonObject;

public class StreamingTurnStreamerTest {

	private final List<String> textDeltas = new ArrayList<>();
	private final List<String> reasoningDeltas = new ArrayList<>();

	private final TurnListener listener = new TurnListener() {
		@Override
		public void onTextDelta(String text) {
			textDeltas.add(text);
		}

		@Override
		public void onReasoningDelta(String text) {
			reasoningDeltas.add(text);
		}
	};

	@Test
	public void testDeltaRelayAndResult() {
		LLMProvider provider = mock(LLMProvider.class);
		when(provider.generateStreamWithTools(any())).thenReturn(Flowable.fromIterable(List.of(
			new StreamEvent.ReasoningDelta("thinking "),
			new StreamEvent.ReasoningDelta("hard"),
			new StreamEvent.TextDelta("Hello "),
			new StreamEvent.TextDelta("world"),
			new StreamEvent.Completed("Hello world"))));

		TurnResult result = new StreamingTurnStreamer(provider).streamTurn(null, listener);

		assertEquals(List.of("Hello ", "world"), textDeltas);
		assertEquals(List.of("thinking ", "hard"), reasoningDeltas);
		assertEquals("Hello world", result.text());
		assertEquals("thinking hard", result.reasoning());
		assertEquals(0, result.toolCalls().size());
	}

	@Test
	public void testToolCallTurn() {
		LLMProvider provider = mock(LLMProvider.class);
		when(provider.generateStreamWithTools(any())).thenReturn(Flowable.fromIterable(List.of(
			new StreamEvent.ToolCallsComplete(List.of(new ToolCall("c1", "search_assets", new JsonObject()))),
			new StreamEvent.Completed(null))));

		TurnResult result = new StreamingTurnStreamer(provider).streamTurn(null, listener);

		assertEquals(1, result.toolCalls().size());
		assertEquals("search_assets", result.toolCalls().get(0).name());
	}

	@Test
	public void testStreamErrorPropagates() {
		LLMProvider provider = mock(LLMProvider.class);
		when(provider.generateStreamWithTools(any())).thenReturn(Flowable.error(new IllegalStateException("down")));

		assertThrows(RuntimeException.class, () -> new StreamingTurnStreamer(provider).streamTurn(null, listener),
			"Stream errors must propagate so the loop can emit a terminal error");
	}

}
