package io.metaloom.loom.agent.chat.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.StreamEvent;
import io.metaloom.ai.genai.llm.ToolCall;
import io.reactivex.rxjava3.core.BackpressureStrategy;
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

	@Test
	public void testCancelMidStreamDisposesUpstream() {
		AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
		LLMProvider provider = mock(LLMProvider.class);
		when(provider.generateStreamWithTools(any())).thenReturn(Flowable.<StreamEvent>create(emitter -> {
			emitter.setCancellable(() -> upstreamCancelled.set(true));
			emitter.onNext(new StreamEvent.TextDelta("Hel"));
			emitter.onNext(new StreamEvent.TextDelta("lo"));
			emitter.onNext(new StreamEvent.Completed("Hello"));
			emitter.onComplete();
		}, BackpressureStrategy.BUFFER));

		StreamingTurnStreamer streamer = new StreamingTurnStreamer(provider);
		// Abort while the first delta is being relayed — the shape of a user pressing stop mid-answer
		TurnResult result = streamer.streamTurn(null, new TurnListener() {
			@Override
			public void onTextDelta(String text) {
				listener.onTextDelta(text);
				streamer.cancel();
			}

			@Override
			public void onReasoningDelta(String text) {
				listener.onReasoningDelta(text);
			}
		});

		assertTrue(upstreamCancelled.get(), "The cancel must dispose the upstream so the provider stops generating");
		assertEquals(List.of("Hel"), textDeltas, "No delta may be relayed after the cancel");
		assertEquals("Hel", result.text(), "The turn returns the text streamed up to the cancel");
	}

	@Test
	public void testCancelFromAnotherThreadUnblocksTurn() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch disposed = new CountDownLatch(1);
		AtomicBoolean upstreamCancelled = new AtomicBoolean(false);

		LLMProvider provider = mock(LLMProvider.class);
		when(provider.generateStreamWithTools(any())).thenReturn(Flowable.<StreamEvent>create(emitter -> {
			emitter.setCancellable(() -> {
				upstreamCancelled.set(true);
				disposed.countDown();
			});
			emitter.onNext(new StreamEvent.TextDelta("streaming"));
			started.countDown();
			// A live provider keeps producing until its stream is closed by the dispose
			disposed.await(30, TimeUnit.SECONDS);
			emitter.onNext(new StreamEvent.TextDelta("late"));
			emitter.onComplete();
		}, BackpressureStrategy.BUFFER));

		StreamingTurnStreamer streamer = new StreamingTurnStreamer(provider);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			// The turn blocks on a worker thread, the abort arrives on another — as it does in production
			Future<TurnResult> turn = executor.submit(() -> streamer.streamTurn(null, listener));
			assertTrue(started.await(30, TimeUnit.SECONDS), "The turn should have started streaming");

			streamer.cancel();

			TurnResult result = turn.get(30, TimeUnit.SECONDS);
			assertTrue(upstreamCancelled.get(), "The cancel must dispose the upstream");
			assertEquals(List.of("streaming"), textDeltas, "No delta may be relayed after the cancel");
			assertEquals("streaming", result.text(), "The turn returns the text streamed up to the cancel");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void testCancelBeforeTurnSkipsProvider() {
		LLMProvider provider = mock(LLMProvider.class);
		StreamingTurnStreamer streamer = new StreamingTurnStreamer(provider);

		streamer.cancel();
		TurnResult result = streamer.streamTurn(null, listener);

		assertEquals(0, result.toolCalls().size());
		assertTrue(textDeltas.isEmpty());
		verify(provider, never()).generateStreamWithTools(any());
	}

}
