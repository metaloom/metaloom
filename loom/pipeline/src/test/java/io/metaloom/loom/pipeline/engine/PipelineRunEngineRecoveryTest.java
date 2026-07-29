package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.TestDescriptors;
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
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media"))
				.add(new JsonObject().put("source", "hash").put("sourcePort", "hash").put("target", "thumb").put("targetPort", "media")));
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
			.put("edges", new JsonArray().add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media")));
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
	void testAHalfFannedItemResumesTheRestOfItsElements() {
		// The process died with the driver settled and one element of the fanned-out node done.
		// Both halves have to survive: the finished element must not run twice, and the two that
		// never ran must not be lost - a fan-out that comes back one element wide leaves the item
		// waiting forever for siblings nobody will dispatch.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineGraphParser portParser = new PipelineGraphParser(TestDescriptors.registry());
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "test-source").put("source", true))
				.add(new JsonObject().put("id", "A").put("type", "splitter"))
				.add(new JsonObject().put("id", "B").put("type", "worker"))
				.add(new JsonObject().put("id", "D").put("type", "collector")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media")
					.put("target", "A").put("targetPort", "media"))
				.add(new JsonObject().put("source", "A").put("sourcePort", "texts")
					.put("target", "B").put("targetPort", "text"))
				.add(new JsonObject().put("source", "B").put("sourcePort", "result")
					.put("target", "D").put("targetPort", "items")));
		PipelineRunEngine engine = new PipelineRunEngine(
			portParser.parse("fanout", definition, true, false, 0), dispatcher, UUID.randomUUID());
		engine.start();

		String itemId = UUID.randomUUID().toString();
		engine.restoreItem(itemId, MediaRef.of("/media/a.mp4"), Map.of(
			"src", done("src"),
			"A", NodeTaskResult.completed(UUID.randomUUID(), "A", 0, 5,
				Payloads.outputs("texts", Payloads.sequence(itemId, TEXT_PLAIN, "p0", "p1", "p2"))),
			"B", NodeTaskResult.completed(UUID.randomUUID(), "B", 0, 5,
				Payloads.outputs("result", Payloads.element(itemId, 0, 3, TEXT_PLAIN, "b0")))),
			Map.of("A", 1, "B", 1));
		engine.resume(true);

		assertEquals(List.of(1, 2), dispatcher.dispatched().stream()
			.filter(t -> t.getNodeId().equals("B")).map(NodeTask::getElementSeq).toList(),
			"Only the elements that never ran are dispatched again");
		assertFalse(dispatcher.wasDispatched("D"), "The gather still waits for the whole branch");

		for (NodeTask task : List.copyOf(dispatcher.dispatched())) {
			if (!task.getNodeId().equals("B")) {
				continue;
			}
			engine.onNodeTaskResult(itemId, NodeTaskResult.completed(task.getTaskUuid(), "B",
				task.getElementSeq(), 5, Payloads.outputs("result",
					Payloads.element(itemId, task.getElementSeq(), 3, TEXT_PLAIN, "b" + task.getElementSeq()))));
		}

		NodeTask gather = dispatcher.taskFor("D");
		assertEquals(List.of("b0", "b1", "b2"), gather.getInputs().get("items").values(),
			"The element recovered from the database gathers alongside the two that were re-run");

		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(gather.getTaskUuid(), "D", 0, 5, Map.of()));
		assertTrue(engine.isComplete(), "A run restarted mid-fan-out must still be able to finish");
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
