package io.metaloom.loom.agent.chat.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProvider;
import io.metaloom.ai.genai.llm.StreamEvent;
import io.metaloom.ai.genai.llm.ToolCall;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subscribers.DisposableSubscriber;

/**
 * Turn streamer backed by {@code generateStreamWithTools} — relays real token and reasoning deltas to the listener as they arrive from the provider.
 * Selected via {@code LOOM_AI_STREAMING=true}; requires a backend that streams tool calls, otherwise the run fails terminally — use the
 * {@link BlockingTurnStreamer} fallback in that case.
 *
 * <p>The subscription is retained so {@link #cancel()} can dispose it mid-turn: disposing runs the provider's cancellable, which closes the upstream HTTP
 * stream and stops generation instead of letting the turn burn tokens after the user pressed stop.</p>
 *
 * <p>One instance serves one run — {@code AgentService} builds a streamer per {@code AgentRequest} — so a cancel is terminal for the whole run.</p>
 */
public class StreamingTurnStreamer implements TurnStreamer {

	private final LLMProvider provider;

	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	/**
	 * The subscriber of the in-flight turn. Published <em>before</em> subscribing: the provider flowable emits on the subscribing thread, so
	 * {@code subscribe(...)} only returns once the stream ended — a handle taken from its return value would always be registered too late to cancel
	 * anything.
	 */
	private final AtomicReference<Disposable> subscription = new AtomicReference<>();

	/** Released by {@code onComplete}/{@code onError} — and by {@link #cancel()}, since RxJava drops terminal events that arrive after a dispose. */
	private final AtomicReference<CountDownLatch> turnLatch = new AtomicReference<>();

	public StreamingTurnStreamer(LLMProvider provider) {
		this.provider = provider;
	}

	@Override
	public TurnResult streamTurn(LLMContext ctx, TurnListener listener) {
		StringBuilder text = new StringBuilder();
		StringBuilder reasoning = new StringBuilder();
		List<ToolCall> toolCalls = new ArrayList<>();
		AtomicReference<String> fullText = new AtomicReference<>();

		// An abort that arrived at a turn boundary must not open another upstream stream.
		if (cancelled.get()) {
			return new TurnResult(null, null, toolCalls);
		}

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		turnLatch.set(done);

		DisposableSubscriber<StreamEvent> subscriber = new DisposableSubscriber<>() {

			@Override
			public void onNext(StreamEvent event) {
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
			}

			@Override
			public void onError(Throwable e) {
				failure.set(e);
				done.countDown();
			}

			@Override
			public void onComplete() {
				done.countDown();
			}
		};
		subscription.set(subscriber);

		try {
			provider.generateStreamWithTools(ctx).subscribe(subscriber);
			done.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			subscriber.dispose();
		} finally {
			subscription.compareAndSet(subscriber, null);
			turnLatch.compareAndSet(done, null);
		}

		Throwable error = failure.get();
		if (error != null) {
			throw error instanceof RuntimeException runtime ? runtime : new RuntimeException("The LLM stream failed", error);
		}

		String finalText = fullText.get() != null ? fullText.get() : text.toString();
		return new TurnResult(finalText, reasoning.isEmpty() ? null : reasoning.toString(), toolCalls);
	}

	@Override
	public String completeText(LLMContext ctx) {
		return provider.generate(ctx);
	}

	@Override
	public void cancel() {
		cancelled.set(true);
		Disposable current = subscription.getAndSet(null);
		if (current != null) {
			current.dispose();
		}
		CountDownLatch latch = turnLatch.get();
		if (latch != null) {
			latch.countDown();
		}
	}

}
