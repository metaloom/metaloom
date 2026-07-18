package io.metaloom.cortex.pipeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineRunContext;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent.Type;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.common.event.DefaultPipelineEventBus;
import io.metaloom.cortex.pipeline.core.executor.ReactivePipelineExecutor;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Verifies that a pipeline execution reports back everything Loom needs to close
 * out its {@code pipeline_run} record: the run correlation id on every tracking
 * event, and a terminal {@code PIPELINE_COMPLETED} carrying a real elapsed
 * duration plus per-media aggregate counters.
 *
 * <p>Before this was wired, the completion event carried no run id and a
 * hardcoded {@code durationMs} of 0, so Loom could not correlate it to a run.</p>
 */
public class PipelineRunCompletionTest {

	private static final String RUN_UUID = "3f1a6c22-8d4e-4b7a-9f61-2c5d8e0a1b34";

	private DefaultPipelineEventBus eventBus;
	private ReactivePipelineExecutor executor;
	private List<PipelineTrackingEvent> events;

	@BeforeEach
	void setup() {
		eventBus = new DefaultPipelineEventBus();
		executor = new ReactivePipelineExecutor(4, eventBus);
		events = new CopyOnWriteArrayList<>();
		eventBus.subscribeTracking(events::add);
	}

	@AfterEach
	void teardown() {
		executor.shutdown();
	}

	private PipelineTrackingEvent completionEvent() {
		return events.stream()
			.filter(e -> e.getType() == Type.PIPELINE_COMPLETED)
			.findFirst()
			.orElseThrow(() -> new AssertionError("No PIPELINE_COMPLETED event was emitted. Got: " + events));
	}

	private static Pipeline pipelineOf(PipelineNode source, PipelineNode... rest) {
		((AbstractPipelineNode) source).setSource(true);
		PipelineNode previous = source;
		for (PipelineNode node : rest) {
			previous.connectTo(node);
			previous = node;
		}
		return DefaultPipeline.builder("run-completion-test").source(source).build();
	}

	private static Flowable<LoomMedia> mediaStream(int count) {
		LoomMedia[] items = new LoomMedia[count];
		for (int i = 0; i < count; i++) {
			items[i] = new StubLoomMedia("/test/media-" + i + ".mp4");
		}
		return Flowable.fromArray(items);
	}

	// ── Run correlation ──────────────────────────────────────────────────

	@Test
	@DisplayName("PIPELINE_COMPLETED carries the run id and a non-zero duration")
	void testCompletionCarriesRunIdAndDuration() {
		Pipeline pipeline = pipelineOf(new WorkNode("hash", 20));

		executor.execute(pipeline, mediaStream(2), PipelineRunContext.of(RUN_UUID))
			.toList().blockingGet();

		PipelineTrackingEvent completed = completionEvent();
		assertThat(completed.getPipelineRunUuid())
			.as("completion event must carry the run id so Loom can correlate it")
			.isEqualTo(RUN_UUID);
		assertThat(completed.getDurationMs())
			.as("two media items sleeping 20ms each must report a real elapsed time")
			.isPositive();
	}

	@Test
	@DisplayName("Every tracking event in a tracked run carries the run id")
	void testAllEventsCarryRunId() {
		Pipeline pipeline = pipelineOf(new WorkNode("hash", 5), new WorkNode("tika", 5));

		executor.execute(pipeline, mediaStream(1), PipelineRunContext.of(RUN_UUID))
			.toList().blockingGet();

		assertThat(events).isNotEmpty();
		assertThat(events)
			.allSatisfy(e -> assertThat(e.getPipelineRunUuid())
				.as("event %s must carry the run id", e.getType())
				.isEqualTo(RUN_UUID));
	}

	@Test
	@DisplayName("An untracked run emits events with a null run id rather than failing")
	void testUntrackedRun() {
		Pipeline pipeline = pipelineOf(new WorkNode("hash", 5));

		executor.execute(pipeline, mediaStream(1), PipelineRunContext.none())
			.toList().blockingGet();

		assertThat(completionEvent().getPipelineRunUuid()).isNull();
	}

	@Test
	@DisplayName("The two-arg execute() overload still works and is untracked")
	void testLegacyOverloadRemainsUntracked() {
		Pipeline pipeline = pipelineOf(new WorkNode("hash", 5));

		executor.execute(pipeline, mediaStream(1)).toList().blockingGet();

		assertThat(completionEvent().getPipelineRunUuid()).isNull();
	}

	// ── Counters ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("All-successful run reports every media item as a success")
	void testCountersAllSuccess() {
		Pipeline pipeline = pipelineOf(new WorkNode("hash", 1));

		executor.execute(pipeline, mediaStream(5), PipelineRunContext.of(RUN_UUID))
			.toList().blockingGet();

		PipelineTrackingEvent.RunCounters counters = completionEvent().getCounters();
		assertThat(counters).isNotNull();
		assertThat(counters.getMediaCount()).isEqualTo(5);
		assertThat(counters.getSuccessCount()).isEqualTo(5);
		assertThat(counters.getFailureCount()).isZero();
		assertThat(counters.getSkippedCount()).isZero();
	}

	@Test
	@DisplayName("A failing node makes every media item count as a failure")
	void testCountersAllFailed() {
		Pipeline pipeline = pipelineOf(new FailingNode("boom"));

		executor.execute(pipeline, mediaStream(3), PipelineRunContext.of(RUN_UUID))
			.toList().blockingGet();

		PipelineTrackingEvent.RunCounters counters = completionEvent().getCounters();
		assertThat(counters.getMediaCount()).isEqualTo(3);
		assertThat(counters.getFailureCount()).isEqualTo(3);
		assertThat(counters.getSuccessCount()).isZero();
	}

	@Test
	@DisplayName("A dry run counts every media item as skipped, not succeeded")
	void testCountersDryRun() {
		WorkNode source = new WorkNode("hash", 1);
		source.setSource(true);
		Pipeline pipeline = DefaultPipeline.builder("dry")
			.source(source)
			.dryRun(true)
			.build();

		executor.execute(pipeline, mediaStream(4), PipelineRunContext.of(RUN_UUID))
			.toList().blockingGet();

		PipelineTrackingEvent.RunCounters counters = completionEvent().getCounters();
		assertThat(counters.getMediaCount()).isEqualTo(4);
		assertThat(counters.getSkippedCount())
			.as("dry-run items did no work, so they are skipped rather than successful")
			.isEqualTo(4);
		assertThat(counters.getSuccessCount()).isZero();
		assertThat(counters.getFailureCount()).isZero();
	}

	@Test
	@DisplayName("Counters are scoped to one execution, not accumulated on the executor")
	void testCountersAreNotAccumulatedAcrossSubscriptions() {
		Pipeline pipeline = pipelineOf(new WorkNode("hash", 1));

		// Two independent subscriptions of the same assembled Flowable.
		Flowable<?> run = executor.execute(pipeline, mediaStream(2), PipelineRunContext.of(RUN_UUID));
		run.toList().blockingGet();
		events.clear();
		run.toList().blockingGet();

		assertThat(completionEvent().getCounters().getMediaCount())
			.as("the second run must report 2 items, not 4")
			.isEqualTo(2);
	}

	// ── Test doubles ─────────────────────────────────────────────────────

	/** A node that does a small amount of real work so durations are non-zero. */
	private static class WorkNode extends AbstractPipelineNode {

		private final long delayMs;

		WorkNode(String id, long delayMs) {
			super(id, id, NodeMode.PARALLEL, true, 4, false, 0);
			this.delayMs = delayMs;
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			try {
				Thread.sleep(delayMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return NodeResult.success(id(), delayMs, Map.of("ok", true));
		}
	}

	/** A node that always throws, to exercise the failure counter. */
	private static class FailingNode extends AbstractPipelineNode {

		FailingNode(String id) {
			super(id, id, NodeMode.PARALLEL, true, 4, false, 0);
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			throw new IllegalStateException("intentional test failure");
		}
	}
}
