package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Per-kind concurrency ceilings.
 *
 * <p>The per-run ceiling stops one run consuming the fleet. This stops one
 * <em>kind</em> consuming a run: without it, a graph containing both transcription
 * and hashing lets the slow kind take every slot while cheap work that could have
 * finished waits behind it.</p>
 *
 * <p>The failure mode to guard hardest is a leaked slot. A kind whose counter drifts
 * upward eventually sits permanently at its ceiling with nothing actually
 * outstanding, which is indistinguishable from a hang — and every path that ends a
 * task without settling it is a chance to leak one.</p>
 */
public class PipelineRunEngineBulkheadTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/** src fans out to two slow nodes and one cheap one, all independent. */
	private PipelineGraph graph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "speech").put("type", "whisper"))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "speech"))
				.add(new JsonObject().put("source", "src").put("target", "hash")));
		return parser.parse("bulkhead", definition, true, false, 0);
	}

	private PipelineRunEngine engine(FakeNodeDispatcher dispatcher) {
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		engine.start();
		return engine;
	}

	private static List<NodeTask> tasksFor(FakeNodeDispatcher dispatcher, String nodeId) {
		return dispatcher.dispatched().stream().filter(t -> t.getNodeId().equals(nodeId)).toList();
	}

	@Test
	void testAKindStopsAtItsCeilingWhileOthersContinue() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);
		engine.setMaxInFlightForKind("whisper", 2);

		for (int i = 0; i < 5; i++) {
			engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4"));
		}

		assertEquals(2, tasksFor(dispatcher, "speech").size(), "The slow kind is capped");
		// The whole point: cheap work is not stuck behind the expensive kind.
		assertEquals(5, tasksFor(dispatcher, "hash").size(), "The cheap kind is unaffected");
	}

	@Test
	void testFinishingOneTaskAdmitsTheNext() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);
		engine.setMaxInFlightForKind("whisper", 1);

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}
		assertEquals(1, tasksFor(dispatcher, "speech").size());

		NodeTask first = tasksFor(dispatcher, "speech").get(0);
		engine.onNodeTaskResult(itemIds.get(0), NodeTaskResult.completed(first.getTaskUuid(), "speech", 5, Map.of()));

		assertEquals(2, tasksFor(dispatcher, "speech").size());
		assertEquals(1, engine.getInFlightForKind("whisper"));
	}

	@Test
	void testNoCeilingMeansUnlimited() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);

		for (int i = 0; i < 8; i++) {
			engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4"));
		}

		// The feature is opt-in; an unconfigured kind behaves exactly as before.
		assertEquals(8, tasksFor(dispatcher, "speech").size());
	}

	@Test
	void testAFailureReleasesTheKindSlot() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);
		engine.setMaxInFlightForKind("whisper", 1);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onItemDiscovered(MediaRef.of("/media/b.mp4"));

		NodeTask task = tasksFor(dispatcher, "speech").get(0);
		engine.onNodeTaskResult(a, NodeTaskResult.failed(task.getTaskUuid(), "speech", 1, "boom"));

		assertEquals(2, tasksFor(dispatcher, "speech").size(), "A failure frees the slot like a success");
		assertEquals(1, engine.getInFlightForKind("whisper"));
	}

	@Test
	void testAReclaimedTaskDoesNotLeakAKindSlot() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);
		engine.setMaxInFlightForKind("whisper", 1);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onItemDiscovered(MediaRef.of("/media/b.mp4"));

		// The worker died; the reaper hands the task back. With no retries left it is
		// dead-lettered, which must return the kind slot as well as the run slot.
		engine.onNodeTaskLost(a, "speech", "lease expired");

		assertEquals(2, tasksFor(dispatcher, "speech").size());
		assertEquals(1, engine.getInFlightForKind("whisper"));
	}

	@Test
	void testARetriedTaskDoesNotLeakAKindSlot() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "speech").put("type", "whisper")
					.put("options", new JsonObject().put("retryFailed", true))))
			.put("edges", new JsonArray().add(new JsonObject().put("source", "src").put("target", "speech")));
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("retry", definition, true, false, 0),
			dispatcher, UUID.randomUUID());
		engine.start();
		engine.setMaxInFlightForKind("whisper", 1);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		NodeTask task = tasksFor(dispatcher, "speech").get(0);
		engine.onNodeTaskResult(a, NodeTaskResult.failed(task.getTaskUuid(), "speech", 1, "transient"));

		// The retry reuses the slot. Counting the failed attempt and the retry
		// separately would leak one per retry, and the kind would wedge at its ceiling.
		assertEquals(2, tasksFor(dispatcher, "speech").size());
		assertEquals(1, engine.getInFlightForKind("whisper"));
	}

	@Test
	void testEverythingIsReleasedWhenTheRunFinishes() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);
		engine.setMaxInFlightForKind("whisper", 2);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		for (NodeTask task : new ArrayList<>(dispatcher.dispatched())) {
			engine.onNodeTaskResult(a, NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, Map.of()));
		}

		assertTrue(engine.isComplete());
		// A non-zero count on a finished run is the signature of a leak.
		assertEquals(0, engine.getInFlightForKind("whisper"));
		assertEquals(0, engine.getInFlightForKind("sha512"));
		assertEquals(0, engine.getInFlightCount());
	}

	@Test
	void testRaisingTheCeilingAdmitsWaitingWorkImmediately() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);
		engine.setMaxInFlightForKind("whisper", 1);

		for (int i = 0; i < 4; i++) {
			engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4"));
		}
		assertEquals(1, tasksFor(dispatcher, "speech").size());

		engine.setMaxInFlightForKind("whisper", 4);

		assertEquals(4, tasksFor(dispatcher, "speech").size(),
			"Raising the ceiling must release waiting work without needing a result");
	}

	@Test
	void testTheRunStillCompletesWithATightCeiling() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher);
		engine.setMaxInFlightForKind("whisper", 1);

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}
		engine.onSourceComplete(3);

		// Drain everything, answering whatever is outstanding until nothing is left.
		int guard = 0;
		while (!engine.isComplete() && guard++ < 100) {
			for (NodeTask task : new ArrayList<>(dispatcher.dispatched())) {
				String itemId = task.getItemId();
				if (engine.getItem(itemId).getResults().containsKey(task.getNodeId())) {
					continue;
				}
				engine.onNodeTaskResult(itemId,
					NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, Map.of()));
			}
		}

		// A ceiling must throttle work, never strand it.
		assertTrue(engine.isComplete(), "A throttled kind must not prevent the run from finishing");
		assertEquals(3, tasksFor(dispatcher, "speech").size());
	}

}
