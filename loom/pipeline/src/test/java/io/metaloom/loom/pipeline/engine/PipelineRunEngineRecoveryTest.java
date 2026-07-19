package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Rebuilding a run from persisted state.
 *
 * <p>Recovery has two ways to be wrong and both are silent. Re-running a node that
 * already completed duplicates side effects — a second thumbnail, a second LLM bill
 * — and skipping one that never ran leaves a gap nothing will fill. These tests pin
 * both directions.</p>
 */
public class PipelineRunEngineRecoveryTest {

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

	private static NodeTaskResult done(String nodeId) {
		return NodeTaskResult.completed(UUID.randomUUID(), nodeId, 5, Map.of());
	}

	@Test
	void testAPartlyDoneItemResumesFromWhereItStopped() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.start();

		String itemId = UUID.randomUUID().toString();
		engine.restoreItem(itemId, MediaRef.of("/media/a.mp4"),
			Map.of("src", done("src"), "hash", done("hash")), Map.of("hash", 1));
		engine.resume(true);

		// Only the unfinished node runs. Re-running 'hash' would recompute a hash that
		// was already stored; skipping 'thumb' would leave the item permanently short.
		assertEquals(List.of("thumb"), dispatcher.dispatchedNodeIds());
	}

	@Test
	void testACompletedItemIsNotDispatchedAtAll() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.start();

		String itemId = UUID.randomUUID().toString();
		engine.restoreItem(itemId, MediaRef.of("/media/a.mp4"),
			Map.of("src", done("src"), "hash", done("hash"), "thumb", done("thumb")), Map.of());
		engine.resume(true);

		assertTrue(dispatcher.dispatched().isEmpty(), "A finished item must not be touched");
		assertTrue(engine.isComplete(), "A run whose items are all done is done");
	}

	@Test
	void testATaskThatWasInFlightIsDispatchedAgain() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.start();

		// 'hash' was RUNNING when the process died, so it was never settled. The worker
		// holding it is gone with the connection - nobody will ever report its result.
		String itemId = UUID.randomUUID().toString();
		engine.restoreItem(itemId, MediaRef.of("/media/a.mp4"), Map.of("src", done("src")), Map.of("hash", 1));
		engine.resume(true);

		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds());
	}

	@Test
	void testTheRetryBudgetSurvivesTheRestart() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")
					.put("options", new JsonObject().put("retryFailed", true).put("maxAttempts", 2))))
			.put("edges", new JsonArray().add(new JsonObject().put("source", "src").put("target", "hash")));
		PipelineRunEngine engine = new PipelineRunEngine(
			parser.parse("retry", definition, true, false, 0), dispatcher, UUID.randomUUID());
		engine.start();

		// One attempt was already spent before the restart.
		String itemId = UUID.randomUUID().toString();
		engine.restoreItem(itemId, MediaRef.of("/media/a.mp4"), Map.of("src", done("src")), Map.of("hash", 1));
		engine.resume(true);

		assertEquals(1, dispatcher.dispatched().size());
		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "still broken"));

		// Without carrying the attempt count across the restart, the budget would reset
		// and a poison item could be retried forever, one restart at a time.
		assertEquals(1, dispatcher.dispatched().size(), "The spent attempt must still count");
		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("hash").getState());
	}

	@Test
	void testARunWhoseSourceNeverFinishedDoesNotCloseEarly() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.start();

		String itemId = UUID.randomUUID().toString();
		engine.restoreItem(itemId, MediaRef.of("/media/a.mp4"),
			Map.of("src", done("src"), "hash", done("hash"), "thumb", done("thumb")), Map.of());
		engine.resume(false);

		// Every known item is finished, but the source had not finished enumerating, so
		// the run is not complete - reporting it as such would present a partial scan as
		// a whole one.
		assertFalse(engine.isComplete(), "An incomplete enumeration must not read as a finished run");
	}

	@Test
	void testRestoredItemsAreAllSeenBeforeAnyDecisionIsMade() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.start();

		String finished = UUID.randomUUID().toString();
		String unfinished = UUID.randomUUID().toString();
		engine.restoreItem(finished, MediaRef.of("/media/a.mp4"),
			Map.of("src", done("src"), "hash", done("hash"), "thumb", done("thumb")), Map.of());
		engine.restoreItem(unfinished, MediaRef.of("/media/b.mp4"), Map.of("src", done("src")), Map.of());

		// If restoreItem dispatched or checked completion as it went, the first item
		// would have closed the run before the second was even read back.
		assertFalse(engine.isComplete(), "Restoring must not complete the run mid-way");
		assertTrue(dispatcher.dispatched().isEmpty(), "Restoring must not dispatch");

		engine.resume(true);
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds());
	}

	@Test
	void testAFailedNodeStillBlocksItsDependentAfterRecovery() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.start();

		String itemId = UUID.randomUUID().toString();
		engine.restoreItem(itemId, MediaRef.of("/media/a.mp4"),
			Map.of("src", done("src"), "hash", NodeTaskResult.failed(UUID.randomUUID(), "hash", 1, "boom")),
			Map.of("hash", 1));
		engine.resume(true);

		// The graph semantics have to survive the round trip through the database, not
		// just hold in memory.
		assertTrue(dispatcher.dispatched().isEmpty(), "thumb blocks on a failed hash");
		assertEquals(NodeState.SKIPPED, engine.getItem(itemId).getResults().get("thumb").getState());
	}

}
