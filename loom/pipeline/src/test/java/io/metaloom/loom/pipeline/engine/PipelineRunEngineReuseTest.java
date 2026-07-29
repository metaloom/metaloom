package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_SHA512;
import static io.metaloom.loom.pipeline.engine.Payloads.outputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Adopting a result an earlier run already produced.
 *
 * <p>The saving is obvious. The two ways it goes wrong are not: reusing a result as
 * a bare <em>skip</em> loses the outputs every downstream node depends on, and
 * reusing one for a file that has since changed makes the pipeline permanently
 * wrong about that file.</p>
 */
public class PipelineRunEngineReuseTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/** Serves a fixed previous result and records what was asked for. */
	private static class StubStore implements RunStateStore {

		final List<String> lookups = new ArrayList<>();
		/** Only this node has a previous result; everything else is genuinely new. */
		String cannedFor = "hash";
		Map<String, PortPayload> canned;

		@Override
		public UUID itemDiscovered(UUID runUuid, long itemSeq, MediaRef media) {
			return UUID.randomUUID();
		}

		@Override
		public void taskDispatched(UUID itemUuid, io.metaloom.loom.pipeline.model.NodeTask task, String workerId) {
		}

		@Override
		public void taskSettled(UUID itemUuid, NodeTaskResult result) {
		}

		@Override
		public void itemSettled(UUID itemUuid, ItemState.ItemOutcome outcome) {
		}

		@Override
		public void sourceCompleted(UUID runUuid, long totalCount) {
		}

		@Override
		public void flush() {
		}

		@Override
		public Optional<NodeTaskResult> previousResult(MediaRef media, String nodeId) {
			lookups.add(nodeId);
			return canned == null || !nodeId.equals(cannedFor)
				? Optional.empty()
				: Optional.of(NodeTaskResult.completed(null, nodeId, 7, canned));
		}
	}

	// src -> hash -> thumb
	private PipelineGraph graph(boolean reuse) {
		JsonObject definition = new JsonObject()
			.put("reuseResults", reuse)
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media"))
				.add(new JsonObject().put("source", "hash").put("sourcePort", "hash").put("target", "thumb").put("targetPort", "media")));
		return parser.parse("reuse", definition, true, false, 0);
	}

	@Test
	void testAKnownResultIsAdoptedInsteadOfDispatched() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		StubStore store = new StubStore();
		store.canned = outputs("hash", HASH_SHA512, "deadbeef");
		PipelineRunEngine engine = new PipelineRunEngine(graph(true), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// 'hash' is adopted; only 'thumb' is actually sent to a worker.
		assertTrue(!dispatcher.wasDispatched("hash"), "A known result must not be recomputed");
		assertTrue(dispatcher.wasDispatched("thumb"));
		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("hash").getState());
	}

	@Test
	void testTheAdoptedResultCarriesItsOutputsForward() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		StubStore store = new StubStore();
		store.canned = outputs("hash", HASH_SHA512, "deadbeef");
		PipelineRunEngine engine = new PipelineRunEngine(graph(true), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// The load-bearing property. Recording a skip instead would leave 'thumb' with
		// no hash in its inputs, and the second run would quietly do less than the first.
		assertEquals("deadbeef", engine.getItem(itemId).getResults().get("hash").output("hash").single());
		assertEquals("deadbeef", dispatcher.taskFor("thumb").getInputs().get("media").single());
	}

	@Test
	void testNothingIsAdoptedWhenTheFlagIsOff() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		StubStore store = new StubStore();
		store.canned = outputs("hash", HASH_SHA512, "deadbeef");
		PipelineRunEngine engine = new PipelineRunEngine(graph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// Opt-in: an existing pipeline must not change behaviour because results happen
		// to exist.
		assertTrue(dispatcher.wasDispatched("hash"));
		assertTrue(store.lookups.isEmpty(), "The store should not even be consulted");
	}

	@Test
	void testAnUnknownResultFallsThroughToDispatch() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		StubStore store = new StubStore();
		store.canned = null;
		PipelineRunEngine engine = new PipelineRunEngine(graph(true), dispatcher, UUID.randomUUID(), store);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		assertTrue(dispatcher.wasDispatched("hash"), "A file nobody has seen must still be processed");
		assertTrue(store.lookups.contains("hash"));
	}

	@Test
	void testADryRunAdoptsNothing() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		StubStore store = new StubStore();
		store.canned = outputs("hash", HASH_SHA512, "deadbeef");
		JsonObject definition = new JsonObject()
			.put("reuseResults", true)
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new JsonArray().add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media")));
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("dry", definition, true, true, 0),
			dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		// A dry run reports what *would* happen. Adopting results would make it report
		// success for work it never intended to do.
		assertEquals(NodeState.SKIPPED, engine.getItem(itemId).getResults().get("hash").getState());
		assertTrue(store.lookups.isEmpty());
	}

	@Test
	void testAFailingLookupFallsBackToRunningTheNode() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		RunStateStore exploding = new StubStore() {
			@Override
			public Optional<NodeTaskResult> previousResult(MediaRef media, String nodeId) {
				throw new IllegalStateException("database gone");
			}
		};
		PipelineRunEngine engine = new PipelineRunEngine(graph(true), dispatcher, UUID.randomUUID(), exploding);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// Reuse is an optimisation. Losing it must cost time, not correctness.
		assertTrue(dispatcher.wasDispatched("hash"));
	}

	@Test
	void testTheRunStillCompletesWhenEveryNodeIsAdopted() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		StubStore store = new StubStore();
		store.canned = outputs("hash", HASH_SHA512, "deadbeef");
		PipelineRunEngine engine = new PipelineRunEngine(graph(true), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		// 'thumb' was dispatched; answer it so nothing is outstanding.
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(
			dispatcher.taskFor("thumb").getTaskUuid(), "thumb", 5, Map.of()));

		assertTrue(engine.isComplete());
		assertEquals(0, engine.getInFlightCount());
	}

	@Test
	void testAnAdoptedNodeDoesNotConsumeAnInFlightSlot() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		StubStore store = new StubStore();
		store.canned = outputs("hash", HASH_SHA512, "deadbeef");
		PipelineRunEngine engine = new PipelineRunEngine(graph(true), dispatcher, UUID.randomUUID(), store);
		engine.setMaxInFlight(1);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// Nothing was sent for 'hash', so it must not hold a slot - otherwise a tight
		// ceiling would be consumed by work that never left the process.
		assertEquals(1, engine.getInFlightCount(), "Only 'thumb' is outstanding");
	}

}
