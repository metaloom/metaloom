package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.engine.RecordingLoomMetrics.Latency;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What the engine reports about itself.
 *
 * <p>
 * The engine had no metrics at all until this existed, and the gap was not cosmetic: dispatch was
 * counted but never timed, so a fleet that had stopped returning results was indistinguishable from
 * one that was merely busy. Every assertion here is that a specific site fires with a specific
 * label — a helper that is registered but never called reads as zero on a dashboard, which is
 * exactly how the previous meters came to be documented and dead.
 * </p>
 */
public class PipelineRunEngineMetricsTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/** src -> hash, where hash carries the given options. */
	private PipelineGraph graph(JsonObject hashOptions) {
		JsonObject hash = new JsonObject().put("id", "hash").put("type", "sha512");
		if (hashOptions != null) {
			hash.put("options", hashOptions);
		}
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(hash))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash")
					.put("targetPort", "media")));
		return parser.parse("metrics", definition, true, false, 0);
	}

	private PipelineRunEngine engine(PipelineGraph graph, FakeNodeDispatcher dispatcher, RecordingLoomMetrics metrics) {
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		engine.setMetrics(metrics);
		return engine;
	}

	@Test
	void testACompletedTaskIsTimedFromDispatchToResult() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		PipelineRunEngine engine = engine(graph(null), dispatcher, metrics);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(task.getTaskUuid(), "hash", 1, Map.of()));

		List<Latency> observed = metrics.latenciesForKind("sha512");
		assertEquals(1, observed.size(), "One settled task, one latency observation");
		assertEquals("completed", observed.get(0).state());
		assertTrue(observed.get(0).durationMs() >= 0, "A latency is a real elapsed time, not a sentinel");
	}

	@Test
	void testTheTimerIsLabelledByKindNotByNodeId() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		PipelineRunEngine engine = engine(graph(null), dispatcher, metrics);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(task.getTaskUuid(), "hash", 1, Map.of()));

		// The node id is whatever the pipeline author typed. Labelling by it would let a new
		// pipeline add series to the registry forever - the cardinality rule the spec sets out.
		assertEquals("sha512", metrics.latencies().get(0).kind());
	}

	@Test
	void testAFailedTaskIsTimedAndLabelledFailed() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		PipelineRunEngine engine = engine(graph(null), dispatcher, metrics);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "boom"));

		// Failures are timed too: a kind that fails fast and a kind that times out are the same
		// count and very different problems.
		assertEquals(1, metrics.latenciesForKind("sha512").size());
		assertEquals("failed", metrics.latencies().get(0).state());
	}

	@Test
	void testADispatchNoWorkerWouldTakeIsNotTimed() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher().rejectKind("sha512");
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		PipelineRunEngine engine = engine(graph(null), dispatcher, metrics);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		// It settles - as a failure, immediately - but it never reached a worker, so there is no
		// dispatch-to-result interval to report. Folding these in would drag the distribution
		// towards zero at exactly the moment the fleet has no capacity, which inverts the signal.
		assertTrue(metrics.latenciesForKind("sha512").isEmpty(),
			"A task no worker accepted has no latency; loom_node_tasks_dispatch_failed_total counts it");
	}

	@Test
	void testARetryIsCountedByKind() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		PipelineRunEngine engine = engine(graph(new JsonObject().put("retryFailed", true)), dispatcher, metrics);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		NodeTask first = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(first.getTaskUuid(), "hash", 1, "transient"));

		assertEquals(1, metrics.retried("sha512"));
		assertEquals(0, metrics.deadlettered("sha512"), "A retry is not a dead-letter");
	}

	@Test
	void testADeadLetterIsCountedByKind() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		PipelineRunEngine engine = engine(graph(null), dispatcher, metrics);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		// The node is not retryable, so the first loss exhausts its budget.
		engine.onNodeTaskLost(itemId, "hash", "lease expired");

		assertEquals(1, metrics.deadlettered("sha512"));
		assertEquals(0, metrics.retried("sha512"));
	}

	@Test
	void testALostTaskIsRetriedBeforeItIsDeadLettered() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		PipelineRunEngine engine = engine(graph(new JsonObject().put("retryFailed", true)), dispatcher, metrics);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		engine.onNodeTaskLost(itemId, "hash", "lease expired");
		assertEquals(1, metrics.retried("sha512"));
		assertEquals(0, metrics.deadlettered("sha512"));

		// Second loss: the budget is spent and it dead-letters. The two counters together are what
		// separates a flaky fleet (retries, no dead-letters) from a broken one.
		engine.onNodeTaskLost(itemId, "hash", "lease expired again");
		assertEquals(1, metrics.retried("sha512"));
		assertEquals(1, metrics.deadlettered("sha512"));
	}

	@Test
	void testInFlightAndItsCeilingAreBothReadable() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(graph(null), dispatcher, new RecordingLoomMetrics());
		engine.setMaxInFlight(7);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// The pair, not the depth alone: 1-of-7 is a healthy run and 7-of-7 is a queue, and the
		// fleet gauges in PipelineRunRegistry are built by summing exactly these two.
		assertEquals(1, engine.getInFlightCount());
		assertEquals(7, engine.getMaxInFlight());
	}

	@Test
	void testAnEngineWithoutAMetricsBackendStillRuns() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(null), dispatcher, UUID.randomUUID());
		// Explicitly cleared, not merely never set: the null branch is the one a caller trips over.
		engine.setMetrics(null);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(task.getTaskUuid(), "hash", 1, Map.of()));

		assertTrue(engine.isComplete());
	}
}
