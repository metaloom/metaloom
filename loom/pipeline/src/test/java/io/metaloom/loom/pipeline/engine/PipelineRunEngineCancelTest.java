package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Cancellation tests for {@link PipelineRunEngine#cancel()}.
 *
 * <p>Cancel stops the engine dispatching <em>new</em> node tasks and settles the run.
 * In-flight tasks cannot be recalled (the dispatcher has no reverse signal), so their
 * late results must be absorbed without spawning downstream work.</p>
 */
public class PipelineRunEngineCancelTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	// src -> hash -> thumb
	private PipelineGraph linearGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash"))
				.add(new JsonObject().put("source", "hash").put("target", "thumb")));
		return parser.parse("linear", definition, true, false, 0);
	}

	private static MediaRef media(String path) {
		return MediaRef.of(path);
	}

	private static NodeTaskResult ok(NodeTask task, Map<String, Object> outputs) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, outputs);
	}

	@Test
	void testCancelStopsFurtherDispatchAndCompletesTheRun() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		// 'hash' is now in flight.
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds());
		assertFalse(engine.isComplete());

		engine.cancel();

		assertTrue(engine.isComplete(), "A cancelled run has reached a terminal state");

		// A late result for the in-flight 'hash' still settles bookkeeping, but must not
		// dispatch 'thumb' downstream.
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), Map.of("sha512", "abc")));
		assertFalse(dispatcher.wasDispatched("thumb"),
			"Cancel must stop new dispatch: a late in-flight result cannot unblock downstream work");
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds());
	}

	@Test
	void testCancelDoesNotFireCompletionListeners() {
		// The caller owns the terminal transition + registry cleanup, so cancel must not
		// drive the normal completion listener (which would derive SUCCESS/PARTIAL/FAILED).
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.cancel();
		// Late result after cancel must not trigger a completion either.
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), Map.of("sha512", "abc")));

		assertNull(summary.get(), "Cancel must not fire completion listeners");
	}

	@Test
	void testCancelIsIdempotent() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4"));
		engine.cancel();
		engine.cancel();

		assertTrue(engine.isComplete());
	}

	@Test
	void testCancelAfterNaturalCompletionIsANoOp() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		AtomicInteger completions = new AtomicInteger();
		engine.onCompletion(s -> completions.incrementAndGet());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), Map.of("sha512", "abc")));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("thumb"), Map.of()));
		assertTrue(engine.isComplete());
		assertEquals(1, completions.get());

		// Cancelling a run that already finished naturally must not disturb it.
		engine.cancel();
		assertEquals(1, completions.get(), "Cancel after completion must not re-fire listeners");
	}

	@Test
	void testItemsDiscoveredAfterCancelAreDropped() {
		// A source still enumerating when the run was cancelled must be ignored rather than
		// crashing with the "source already complete" IllegalStateException.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4"));
		engine.cancel();

		String late = engine.onItemDiscovered(media("/media/b.mp4"));
		assertNull(late, "An item discovered after cancel is dropped");
		assertNotNull(engine.getItems());
		assertFalse(dispatcher.wasDispatched("thumb"));
		// No task for the dropped item's hash was dispatched.
		assertEquals(1, dispatcher.dispatched().stream().filter(t -> t.getNodeId().equals("hash")).count(),
			"No new work is dispatched for an item discovered after cancel");
	}

	@Test
	void testCancelReleasesCapacityWaiters() {
		// A source held for capacity must be released on cancel, or it waits forever on a
		// run that has stopped consuming.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(1);

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4")); // occupies the single slot with 'hash'
		assertTrue(engine.isAtCapacity());

		AtomicInteger released = new AtomicInteger();
		engine.whenCapacityAvailable(released::incrementAndGet);
		assertEquals(0, released.get(), "The waiter is parked while at capacity");

		engine.cancel();
		assertEquals(1, released.get(), "Cancel must release capacity waiters");
	}

	@Test
	void testLateResultAfterCancelStillSettlesTheNode() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.cancel();
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), Map.of("sha512", "abc")));

		assertEquals(NodeState.COMPLETED, engine.getItem(item).getResults().get("hash").getState(),
			"A late in-flight result still settles its own node's bookkeeping");
	}
}
