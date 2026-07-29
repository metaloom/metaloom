package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_SHA512;
import static io.metaloom.loom.pipeline.engine.Payloads.outputs;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link PipelineRunEngine#pause()} / {@link PipelineRunEngine#unpause()}.
 *
 * <p>Pause differs from cancel in being reversible and non-terminal: the run keeps all of
 * its state and can be brought back. The properties worth pinning are that it stops new
 * dispatch, that it also withholds the source acknowledgement (otherwise the scan keeps
 * running and pause is cosmetic), and that it never completes the run behind the operator's
 * back.</p>
 */
public class PipelineRunEnginePauseTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	// src -> hash -> thumb
	private PipelineGraph linearGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media"))
				.add(new JsonObject().put("source", "hash").put("sourcePort", "hash").put("target", "thumb").put("targetPort", "media")));
		return parser.parse("linear", definition, true, false, 0);
	}

	private static MediaRef media(String path) {
		return MediaRef.of(path);
	}

	private static NodeTaskResult ok(NodeTask task, Map<String, PortPayload> outputs) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, outputs);
	}

	@Test
	@DisplayName("pause stops new dispatch; a late in-flight result settles but unblocks nothing")
	void testPauseStopsDispatch() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds());

		assertTrue(engine.pause());
		assertTrue(engine.isPaused());

		// 'hash' comes back while suspended. Its own bookkeeping must settle...
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "abc")));
		assertEquals(NodeState.COMPLETED, engine.getItem(item).getResults().get("hash").getState(),
			"A result arriving during a pause still settles its own node");
		// ...but 'thumb' must not be dispatched.
		assertFalse(dispatcher.wasDispatched("thumb"),
			"Pause must stop new dispatch even when a dependency settles");
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds());
	}

	@Test
	@DisplayName("unpause dispatches everything that became ready while suspended")
	void testUnpauseDispatchesWhatBecameReady() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.pause();
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "abc")));
		assertFalse(dispatcher.wasDispatched("thumb"));

		engine.unpause();

		assertFalse(engine.isPaused());
		assertTrue(dispatcher.wasDispatched("thumb"), "Unpause must dispatch work that became ready during the pause");
	}

	@Test
	@DisplayName("pause does not complete the run, even once the source is done")
	void testPauseDoesNotCompleteTheRun() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.pause();
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "abc")));

		// 'thumb' is ready but undispatched, so the item is not complete and neither is the run.
		assertFalse(engine.isComplete(), "A paused run with outstanding work must not complete");
		assertEquals(null, summary.get(), "A paused run must not fire completion listeners");
	}

	@Test
	@DisplayName("pause holds capacity waiters even when there is free capacity")
	void testPauseHoldsCapacityWaitersWithFreeCapacity() {
		// This is the property that makes pause real rather than cosmetic. Gating dispatch
		// alone would leave the source free to keep scanning; withholding the acknowledgement
		// is what stops the scan.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(100);

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4"));
		assertFalse(engine.isAtCapacity(), "Precondition: there is plenty of room");

		engine.pause();

		AtomicInteger released = new AtomicInteger();
		engine.whenCapacityAvailable(released::incrementAndGet);
		assertEquals(0, released.get(), "A paused run parks the source even with capacity to spare");

		engine.unpause();
		assertEquals(1, released.get(), "Unpause releases the held source");
	}

	@Test
	@DisplayName("a run paused while at capacity still holds its waiter after capacity frees up")
	void testPauseHoldsWaiterWhenCapacityFrees() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(1);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		assertTrue(engine.isAtCapacity());

		AtomicInteger released = new AtomicInteger();
		engine.whenCapacityAvailable(released::incrementAndGet);
		assertEquals(0, released.get());

		engine.pause();
		// The in-flight task returns, freeing the slot. Without the pause gate in
		// releaseCapacityWaiters() this would let the source carry on scanning.
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "abc")));
		assertEquals(0, released.get(), "Freeing capacity during a pause must not release the source");

		engine.unpause();
		assertEquals(1, released.get());
	}

	@Test
	@DisplayName("pause is idempotent and unpause on a running run is a no-op")
	void testIdempotence() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4"));

		engine.unpause(); // not paused: no-op
		assertFalse(engine.isPaused());

		assertTrue(engine.pause());
		assertTrue(engine.pause(), "pause() is idempotent");
		assertTrue(engine.isPaused());

		engine.unpause();
		engine.unpause();
		assertFalse(engine.isPaused());
	}

	@Test
	@DisplayName("pausing a completed run is refused and does not disturb it")
	void testPauseAfterCompletionIsRefused() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		AtomicInteger completions = new AtomicInteger();
		engine.onCompletion(s -> completions.incrementAndGet());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "abc")));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("thumb"), Map.of()));
		assertTrue(engine.isComplete());

		assertFalse(engine.pause(), "pause() reports failure on a run that already finished");
		assertFalse(engine.isPaused());
		assertEquals(1, completions.get());
	}

	@Test
	@DisplayName("cancelling a paused run works and releases the held source")
	void testCancelWhilePaused() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(1);

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4"));
		AtomicInteger released = new AtomicInteger();
		engine.whenCapacityAvailable(released::incrementAndGet);

		engine.pause();
		assertEquals(0, released.get());

		engine.cancel();

		assertTrue(engine.isComplete(), "A cancelled run is terminal even if it was paused");
		assertFalse(engine.isPaused(), "Cancel clears the suspension");
		assertEquals(1, released.get(), "Cancelling a paused run must not strand the source");
	}

	@Test
	@DisplayName("a paused run whose last outstanding work settles completes anyway")
	void testPausedRunCompletesWhenNothingIsLeft() {
		// Only 'thumb' was outstanding and it came back during the pause. There is now
		// genuinely nothing left to resume, so the run closes out rather than sitting in
		// PAUSED forever waiting for an operator to unpause a finished run.
		//
		// Pause suppresses *dispatch*, not *completion* - those are different questions,
		// and conflating them would strand runs.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "abc")));
		// 'thumb' is now in flight.
		engine.pause();
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("thumb"), Map.of()));

		assertTrue(engine.isComplete(), "A paused run with no outstanding work still completes");
		assertFalse(engine.isPaused(), "Completion clears the suspension so the state stays coherent");
		assertEquals(1, summary.get().getMediaCount());

		// Unpausing a run that completed while paused must not disturb it.
		engine.unpause();
		assertTrue(engine.isComplete());
	}

	@Test
	@DisplayName("items discovered while paused are recorded but nothing is dispatched for them")
	void testItemsDiscoveredWhilePausedAreNotDispatched() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		engine.pause();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));

		// Unlike cancel, pause keeps the item: it is legitimate work, merely deferred.
		assertTrue(engine.getItems().containsKey(item), "A paused run still records discovered items");
		assertTrue(dispatcher.dispatchedNodeIds().isEmpty(), "Nothing is dispatched while paused");

		engine.unpause();
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds(),
			"Unpause dispatches the work discovered during the pause");
	}
}
