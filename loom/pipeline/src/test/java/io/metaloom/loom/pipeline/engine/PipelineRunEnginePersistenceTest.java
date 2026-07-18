package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.engine.ItemState.ItemOutcome;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What the engine reports to its {@link RunStateStore}.
 *
 * <p>These are the tests that matter for restart recovery. The engine can be
 * perfectly correct in memory and still leave a run unrecoverable if it settles a
 * node without telling the store - so each path that settles a node (dispatch,
 * skip, refused dispatch) is checked separately rather than relying on the happy
 * path to cover them all.</p>
 */
public class PipelineRunEnginePersistenceTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	// src -> hash -> thumb
	private PipelineGraph linearGraph(boolean dryRun) {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash"))
				.add(new JsonObject().put("source", "hash").put("target", "thumb")));
		return parser.parse("linear", definition, true, dryRun, 0);
	}

	@Test
	void testItemIdentityComesFromTheStore() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		UUID runUuid = UUID.randomUUID();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, runUuid, store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		assertEquals(1, store.discovered.size());
		RecordingRunStateStore.Discovered d = store.discovered.get(0);
		assertEquals(1, d.seq(), "Discovery order starts at 1 and is what the unique constraint keys on");
		assertEquals("/media/a.mp4", d.media().getPath());

		// The engine must adopt the store's id verbatim. If it kept its own counter,
		// a result arriving after a restart could not be matched to its item.
		assertEquals(d.itemUuid().toString(), itemId);
	}

	@Test
	void testEveryNodeIsRecordedIncludingTheSynthesisedSource() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		UUID itemUuid = UUID.fromString(itemId);

		// The source is synthesised rather than dispatched, but it still has to be
		// recorded - otherwise recovery cannot tell that the graph has a settled root.
		assertEquals(List.of("src"), store.settledNodes(itemUuid));

		NodeTask hashTask = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(hashTask.getTaskUuid(), "hash", 5, Map.of("sha512", "x")));
		NodeTask thumbTask = dispatcher.taskFor("thumb");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(thumbTask.getTaskUuid(), "thumb", 5, Map.of()));

		assertEquals(List.of("src", "hash", "thumb"), store.settledNodes(itemUuid));
	}

	@Test
	void testDispatchIsRecordedBeforeTheResultArrives() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// A task must be durable at the moment it is handed out, not when it comes
		// back. A worker that dies holding an unrecorded task is invisible work.
		assertEquals(1, store.dispatched.size());
		assertEquals("hash", store.dispatched.get(0).getNodeId());
		assertTrue(store.settledNodes(UUID.fromString(store.discovered.get(0).itemUuid().toString())).contains("src"));
	}

	@Test
	void testSkippedNodesAreRecordedToo() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		UUID itemUuid = UUID.fromString(itemId);

		// Fail the hash so thumb is skipped rather than dispatched.
		NodeTask hashTask = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(hashTask.getTaskUuid(), "hash", 1, "boom"));

		// A skip is a decision, not an absence of one. Without recording it, recovery
		// would see an unsettled node and re-dispatch work the engine already declined.
		assertEquals(List.of("src", "hash", "thumb"), store.settledNodes(itemUuid));
		assertEquals(io.metaloom.loom.pipeline.model.NodeState.SKIPPED,
			store.settled.get(2).result().getState());
	}

	@Test
	void testRefusedDispatchIsRecordedAsSettled() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		// Refuses everything, as an empty worker pool would.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher().rejectAll();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		UUID itemUuid = UUID.fromString(itemId);
		assertEquals(List.of("src", "hash", "thumb"), store.settledNodes(itemUuid),
			"A node no worker would take is still a settled node");
		assertEquals(ItemOutcome.FAILURE, store.itemOutcomes.get(itemUuid));
	}

	@Test
	void testItemIsSettledOnceEveryNodeIsDone() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		UUID itemUuid = UUID.fromString(itemId);

		assertTrue(store.itemOutcomes.isEmpty(), "An item mid-graph is not settled");

		NodeTask hashTask = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(hashTask.getTaskUuid(), "hash", 5, Map.of()));
		NodeTask thumbTask = dispatcher.taskFor("thumb");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(thumbTask.getTaskUuid(), "thumb", 5, Map.of()));

		assertEquals(ItemOutcome.SUCCESS, store.itemOutcomes.get(itemUuid));
	}

	@Test
	void testStoreIsFlushedWhenTheRunCompletes() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		assertEquals(0, store.flushCount, "Nothing to flush while the run is still open");

		NodeTask hashTask = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(hashTask.getTaskUuid(), "hash", 5, Map.of()));
		NodeTask thumbTask = dispatcher.taskFor("thumb");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(thumbTask.getTaskUuid(), "thumb", 5, Map.of()));

		assertTrue(engine.isComplete());
		// A batching store that is never drained loses precisely the end of the run.
		assertEquals(1, store.flushCount);
	}

	@Test
	void testDryRunRecordsSkipsRatherThanSilence() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(true), dispatcher, UUID.randomUUID(), store);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		UUID itemUuid = UUID.fromString(itemId);
		assertEquals(List.of("src", "hash", "thumb"), store.settledNodes(itemUuid));
		assertEquals(0, store.dispatched.size(), "A dry run dispatches nothing");
		assertEquals(ItemOutcome.SKIPPED, store.itemOutcomes.get(itemUuid));
	}

	@Test
	void testEachItemGetsItsOwnIdentityAndSequence() {
		RecordingRunStateStore store = new RecordingRunStateStore();
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID(), store);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onItemDiscovered(MediaRef.of("/media/b.mp4"));

		assertEquals(2, store.discovered.size());
		assertEquals(1, store.discovered.get(0).seq());
		assertEquals(2, store.discovered.get(1).seq());
		assertNotNull(store.discovered.get(0).itemUuid());
		assertTrue(!store.discovered.get(0).itemUuid().equals(store.discovered.get(1).itemUuid()),
			"Two items must not share an id - their node tasks would collide");
	}

}
