package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The in-flight ceiling.
 *
 * <p>Without a bound, a fast source and slow nodes produce unbounded outstanding
 * work — a 100 000 item scan dispatches every ready node at once and one run
 * consumes the whole fleet. The subtle failure is the opposite one: a cap that
 * leaks slots eventually wedges a run at capacity with nothing actually
 * outstanding, which looks exactly like a hang.</p>
 */
public class PipelineRunEngineFlowControlTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	private PipelineGraph singleNodeGraph(JsonObject hashOptions) {
		JsonObject hash = new JsonObject().put("id", "hash").put("type", "sha512");
		if (hashOptions != null) {
			hash.put("options", hashOptions);
		}
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(hash))
			.put("edges", new JsonArray().add(new JsonObject().put("source", "src").put("target", "hash")));
		return parser.parse("flow", definition, true, false, 0);
	}

	private PipelineRunEngine engine(FakeNodeDispatcher dispatcher, int cap, JsonObject hashOptions) {
		PipelineRunEngine engine = new PipelineRunEngine(singleNodeGraph(hashOptions), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(cap);
		engine.start();
		return engine;
	}

	@Test
	void testDispatchStopsAtTheCeiling() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 3, null);

		for (int i = 0; i < 10; i++) {
			engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4"));
		}

		assertEquals(3, dispatcher.dispatched().size(), "Ten ready items must not all be dispatched at once");
		assertTrue(engine.isAtCapacity());
	}

	@Test
	void testFinishingATaskReleasesCapacityForAnotherItem() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 2, null);

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}
		assertEquals(2, dispatcher.dispatched().size());

		// Completing item 0's node must let a *different* item's deferred node run.
		// Without a sweep across items, deferred work would only resume when its own
		// item progressed - which it cannot, because it is blocked.
		NodeTask first = dispatcher.dispatched().get(0);
		engine.onNodeTaskResult(itemIds.get(0), NodeTaskResult.completed(first.getTaskUuid(), "hash", 5, Map.of()));

		assertEquals(3, dispatcher.dispatched().size());
	}

	@Test
	void testTheWholeBacklogDrainsAsResultsArrive() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 2, null);

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}
		engine.onSourceComplete(6);

		int settled = 0;
		while (settled < dispatcher.dispatched().size()) {
			NodeTask task = dispatcher.dispatched().get(settled);
			String itemId = itemIds.stream()
				.filter(id -> id.equals(task.getItemId()))
				.findFirst().orElseThrow();
			engine.onNodeTaskResult(itemId, NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, Map.of()));
			settled++;
		}

		assertEquals(6, dispatcher.dispatched().size(), "Every item must eventually be dispatched");
		assertTrue(engine.isComplete());
		assertEquals(0, engine.getInFlightCount(), "Nothing may remain outstanding once the run is done");
	}

	@Test
	void testAFailedTaskReleasesItsSlot() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 1, null);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onItemDiscovered(MediaRef.of("/media/b.mp4"));
		assertEquals(1, dispatcher.dispatched().size());

		NodeTask task = dispatcher.dispatched().get(0);
		engine.onNodeTaskResult(a, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "boom"));

		assertEquals(2, dispatcher.dispatched().size(), "A failure must free the slot like a success does");
	}

	@Test
	void testARetriedTaskDoesNotLeakItsSlot() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 1, new JsonObject().put("retryFailed", true));

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		NodeTask task = dispatcher.dispatched().get(0);
		engine.onNodeTaskResult(a, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "transient"));

		// The retry re-dispatches, so exactly one slot is in use - not two. Counting the
		// failed attempt and the retry separately would leak a slot per retry, and a run
		// with enough retries would wedge permanently at capacity.
		assertEquals(2, dispatcher.dispatched().size());
		assertEquals(1, engine.getInFlightCount(), "A retry reuses the slot rather than consuming a second");
	}

	@Test
	void testAReclaimedTaskDoesNotLeakItsSlot() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 1, null);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onItemDiscovered(MediaRef.of("/media/b.mp4"));
		assertEquals(1, dispatcher.dispatched().size());

		// The worker died; the reaper hands the task back. With no retries left it is
		// dead-lettered, which must return the slot to the pool.
		engine.onNodeTaskLost(a, "hash", "lease expired");

		assertEquals(2, dispatcher.dispatched().size(), "The freed slot must go to the waiting item");
		assertEquals(1, engine.getInFlightCount());
	}

	@Test
	void testRaisingTheCeilingReleasesDeferredWorkImmediately() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 1, null);

		for (int i = 0; i < 4; i++) {
			engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4"));
		}
		assertEquals(1, dispatcher.dispatched().size());

		engine.setMaxInFlight(4);

		assertEquals(4, dispatcher.dispatched().size(), "Deferred work must resume without waiting for a result");
	}

	@Test
	void testACeilingOfZeroMeansUnlimited() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 0, null);

		for (int i = 0; i < 20; i++) {
			engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4"));
		}

		assertEquals(20, dispatcher.dispatched().size());
		assertFalse(engine.isAtCapacity());
	}

	@Test
	void testDeferredItemsDoNotCountAsComplete() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 1, null);

		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onItemDiscovered(MediaRef.of("/media/b.mp4"));
		engine.onSourceComplete(2);

		// Item b's node is deferred, not settled. Treating a deferred node as finished
		// would close the run having silently skipped it.
		assertFalse(engine.isComplete());
	}

}
