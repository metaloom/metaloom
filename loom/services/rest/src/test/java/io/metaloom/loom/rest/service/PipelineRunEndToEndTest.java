package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.engine.NodeDispatcher;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.engine.RunSummary;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.rest.model.processor.message.NodeTaskResultMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.SourceCompleteMessage;
import io.metaloom.loom.rest.model.processor.message.SourceItemsMessage;
import io.metaloom.loom.rest.service.impl.PipelineRunRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Drives a whole run through the Variant C path, from stored definition to closed
 * run, with a scripted worker standing in for Cortex.
 *
 * <p>This is the test the feature has never had. The defect that broke it end to
 * end — Loom writing {@code edges[]} while Cortex read {@code dependencies[]} —
 * survived precisely because nothing exercised a stored definition all the way
 * through execution. Everything here starts from the JSON the UI actually writes.</p>
 *
 * <p>The transport is deliberately left out: messages are handed straight to the
 * engine and the registry, the same way {@code ProcessorEndpoint} does after
 * decoding a frame. What is under test is the orchestration, not the socket.</p>
 */
public class PipelineRunEndToEndTest {

	/** The exact shape the UI writes and DemoDatabaseInitializer seeds. */
	private static JsonObject demoDefinition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source")
					.put("name", "File Source").put("source", true)
					.put("options", new JsonObject().put("path", "/media")))
				.add(new JsonObject().put("id", "pn2").put("type", "sha256").put("name", "SHA-256"))
				.add(new JsonObject().put("id", "pn3").put("type", "thumbnail").put("name", "Thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("target", "pn2"))
				.add(new JsonObject().put("id", "pe2").put("source", "pn2").put("target", "pn3")));
	}

	/**
	 * A scripted Cortex: records dispatched tasks and answers them on demand, the
	 * way a worker would over the WebSocket.
	 */
	private static class ScriptedWorker implements NodeDispatcher {

		private final List<NodeTask> inbox = new ArrayList<>();

		@Override
		public boolean dispatch(NodeTask task) {
			inbox.add(task);
			return true;
		}

		NodeTask take(String nodeId) {
			return inbox.stream().filter(t -> t.getNodeId().equals(nodeId)).findFirst()
				.orElseThrow(() -> new AssertionError("No task dispatched for '" + nodeId
					+ "'. Dispatched: " + inbox.stream().map(NodeTask::getNodeId).toList()));
		}

		boolean sawTaskFor(String nodeId) {
			return inbox.stream().anyMatch(t -> t.getNodeId().equals(nodeId));
		}

		/** Answer a task the way NODE_TASK_RESULT would arrive. */
		NodeTaskResultMessage reply(NodeTask task, Map<String, Object> outputs) {
			return new NodeTaskResultMessage()
				.setRunUuid(task.getRunUuid())
				.setItemId(task.getItemId())
				.setResult(NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, outputs));
		}

		NodeTaskResultMessage replyFailed(NodeTask task, String message) {
			return new NodeTaskResultMessage()
				.setRunUuid(task.getRunUuid())
				.setItemId(task.getItemId())
				.setResult(NodeTaskResult.failed(task.getTaskUuid(), task.getNodeId(), 5, message));
		}
	}

	/** Mirrors ProcessorEndpoint.handleNodeTaskResult. */
	private static void deliver(PipelineRunRegistry registry, NodeTaskResultMessage message) {
		PipelineRunEngine engine = registry.get(message.getRunUuid());
		assertNotNull(engine, "The run should still be registered");
		engine.onNodeTaskResult(message.getItemId(), message.getResult());
	}

	/** Mirrors ProcessorEndpoint.handleSourceItems. */
	private static List<String> deliver(PipelineRunRegistry registry, SourceItemsMessage message) {
		PipelineRunEngine engine = registry.get(message.getRunUuid());
		assertNotNull(engine, "The run should still be registered");
		List<String> itemIds = new ArrayList<>();
		message.getItems().forEach(item -> itemIds.add(engine.onItemDiscovered(item)));
		return itemIds;
	}

	/** Mirrors ProcessorEndpoint.handleSourceComplete. */
	private static void deliver(PipelineRunRegistry registry, SourceCompleteMessage message) {
		PipelineRunEngine engine = registry.get(message.getRunUuid());
		if (engine != null) {
			engine.onSourceComplete(message.getTotalCount());
		}
	}

	@Test
	void testFullRunFromStoredDefinitionToClosedRun() {
		UUID runUuid = UUID.randomUUID();
		PipelineGraph graph = new PipelineGraphParser().parse("demo", demoDefinition(), true, false, 100);

		// The whole graph must survive parsing. A size of 1 is the original defect.
		assertEquals(3, graph.size());

		ScriptedWorker worker = new ScriptedWorker();
		PipelineRunRegistry registry = new PipelineRunRegistry();
		AtomicReference<RunSummary> closed = new AtomicReference<>();

		PipelineRunEngine engine = new PipelineRunEngine(graph, worker, runUuid);
		engine.onCompletion(closed::set);
		registry.register(runUuid, engine);
		engine.start();

		// --- the worker enumerates two files
		List<String> items = deliver(registry, new SourceItemsMessage()
			.setRunUuid(runUuid).setSeq(0)
			.setItems(List.of(MediaRef.of("/media/a.mp4"), MediaRef.of("/media/b.mp4"))));
		deliver(registry, new SourceCompleteMessage().setRunUuid(runUuid).setTotalCount(2));

		assertEquals(2, items.size());
		assertFalse(worker.sawTaskFor("pn1"), "The source result is synthesised, not dispatched");

		// --- sha256 runs for both items, then thumbnail
		for (String itemId : items) {
			NodeTask hash = worker.inbox.stream()
				.filter(t -> t.getNodeId().equals("pn2") && t.getItemId().equals(itemId))
				.findFirst().orElseThrow(() -> new AssertionError("No sha256 task for " + itemId));
			deliver(registry, worker.reply(hash, Map.of("sha256", "hash-of-" + itemId)));
		}

		for (String itemId : items) {
			NodeTask thumb = worker.inbox.stream()
				.filter(t -> t.getNodeId().equals("pn3") && t.getItemId().equals(itemId))
				.findFirst().orElseThrow(() -> new AssertionError("No thumbnail task for " + itemId));
			// The graph is honoured: the downstream node sees its upstream's output.
			assertEquals("hash-of-" + itemId, thumb.getUpstreamOutputs().get("pn2").get("sha256"));
			deliver(registry, worker.reply(thumb, Map.of("thumbnail", "/thumbs/" + itemId + ".jpg")));
		}

		// --- the run closes itself
		assertTrue(engine.isComplete());
		assertNotNull(closed.get(), "Completion must fire so the run leaves RUNNING");
		assertEquals(2, closed.get().getMediaCount());
		assertEquals(2, closed.get().getSuccessCount());
		assertEquals(0, closed.get().getFailureCount());
		assertEquals("SUCCESS", closed.get().getStatus());

		assertEquals(0, registry.activeRunCount(),
			"A finished run must be unregistered, or the map grows for the process lifetime");
	}

	@Test
	void testDownstreamIsSkippedWhenAnUpstreamNodeFails() {
		UUID runUuid = UUID.randomUUID();
		PipelineGraph graph = new PipelineGraphParser().parse("demo", demoDefinition(), true, false, 100);
		ScriptedWorker worker = new ScriptedWorker();
		PipelineRunRegistry registry = new PipelineRunRegistry();
		AtomicReference<RunSummary> closed = new AtomicReference<>();

		PipelineRunEngine engine = new PipelineRunEngine(graph, worker, runUuid);
		engine.onCompletion(closed::set);
		registry.register(runUuid, engine);
		engine.start();

		List<String> items = deliver(registry, new SourceItemsMessage()
			.setRunUuid(runUuid).setSeq(0).setItems(List.of(MediaRef.of("/media/broken.mp4"))));
		deliver(registry, new SourceCompleteMessage().setRunUuid(runUuid).setTotalCount(1));

		deliver(registry, worker.replyFailed(worker.take("pn2"), "unreadable"));

		assertFalse(worker.sawTaskFor("pn3"),
			"thumbnail blocks on sha256, so a failure upstream must skip it rather than run it");
		assertEquals(NodeState.SKIPPED,
			engine.getItem(items.get(0)).getResults().get("pn3").getState());
		assertEquals("FAILED", closed.get().getStatus());
	}

	@Test
	void testDryRunDispatchesNothingAndStillClosesTheRun() {
		UUID runUuid = UUID.randomUUID();
		PipelineGraph graph = new PipelineGraphParser().parse("demo", demoDefinition(), true, true, 100);
		ScriptedWorker worker = new ScriptedWorker();
		PipelineRunRegistry registry = new PipelineRunRegistry();
		AtomicReference<RunSummary> closed = new AtomicReference<>();

		PipelineRunEngine engine = new PipelineRunEngine(graph, worker, runUuid);
		engine.onCompletion(closed::set);
		registry.register(runUuid, engine);
		engine.start();

		deliver(registry, new SourceItemsMessage()
			.setRunUuid(runUuid).setSeq(0).setItems(List.of(MediaRef.of("/media/a.mp4"))));
		deliver(registry, new SourceCompleteMessage().setRunUuid(runUuid).setTotalCount(1));

		assertTrue(worker.inbox.isEmpty(), "A dry run must not dispatch any work");
		assertEquals(1, closed.get().getSkippedCount());
		assertEquals("SUCCESS", closed.get().getStatus());
	}

	@Test
	void testEmptySourceClosesTheRunImmediately() {
		UUID runUuid = UUID.randomUUID();
		PipelineGraph graph = new PipelineGraphParser().parse("demo", demoDefinition(), true, false, 100);
		ScriptedWorker worker = new ScriptedWorker();
		PipelineRunRegistry registry = new PipelineRunRegistry();
		AtomicReference<RunSummary> closed = new AtomicReference<>();

		PipelineRunEngine engine = new PipelineRunEngine(graph, worker, runUuid);
		engine.onCompletion(closed::set);
		registry.register(runUuid, engine);
		engine.start();

		deliver(registry, new SourceCompleteMessage().setRunUuid(runUuid).setTotalCount(0));

		assertTrue(engine.isComplete(),
			"A selection that matched nothing must close the run, not leave it RUNNING");
		assertEquals(0, closed.get().getMediaCount());
		assertEquals("SUCCESS", closed.get().getStatus());
	}

	@Test
	void testLateMessageForAFinishedRunIsIgnored() {
		UUID runUuid = UUID.randomUUID();
		PipelineGraph graph = new PipelineGraphParser().parse("demo", demoDefinition(), true, false, 100);
		PipelineRunRegistry registry = new PipelineRunRegistry();
		PipelineRunEngine engine = new PipelineRunEngine(graph, new ScriptedWorker(), runUuid);
		registry.register(runUuid, engine);
		engine.start();
		deliver(registry, new SourceCompleteMessage().setRunUuid(runUuid).setTotalCount(0));

		// The run is gone from the registry; a straggler must not resurrect it or throw.
		assertEquals(null, registry.get(runUuid));
		deliver(registry, new SourceCompleteMessage().setRunUuid(runUuid).setTotalCount(0));
	}

	@Test
	void testTaskCarriesTheNodeKindTheWorkerNeedsToResolve() {
		UUID runUuid = UUID.randomUUID();
		PipelineGraph graph = new PipelineGraphParser().parse("demo", demoDefinition(), true, false, 100);
		ScriptedWorker worker = new ScriptedWorker();
		PipelineRunRegistry registry = new PipelineRunRegistry();

		PipelineRunEngine engine = new PipelineRunEngine(graph, worker, runUuid);
		registry.register(runUuid, engine);
		engine.start();
		deliver(registry, new SourceItemsMessage()
			.setRunUuid(runUuid).setSeq(0).setItems(List.of(MediaRef.of("/media/a.mp4"))));

		NodeTask task = worker.take("pn2");
		assertEquals("sha256", task.getNodeKind(),
			"The worker resolves an implementation from the kind, not the graph id");
		assertEquals(runUuid, task.getRunUuid());
		assertEquals("/media/a.mp4", task.getMedia().getPath());
	}

	@Test
	void testProtocolMessagesRoundTripThroughTheEnvelope() {
		// The same objects the endpoint decodes, encoded as the wire would carry them.
		UUID runUuid = UUID.randomUUID();
		SourceItemsMessage items = new SourceItemsMessage().setRunUuid(runUuid).setSeq(0)
			.setItems(List.of(MediaRef.of("/media/a.mp4")));

		ProcessorMessage envelope = new ProcessorMessage(ProcessorMessageType.SOURCE_ITEMS,
			JsonObject.mapFrom(items));
		SourceItemsMessage decoded = new JsonObject(io.vertx.core.json.Json.encode(envelope))
			.mapTo(ProcessorMessage.class).getBody().mapTo(SourceItemsMessage.class);

		assertEquals(runUuid, decoded.getRunUuid());
		assertEquals("/media/a.mp4", decoded.getItems().get(0).getPath());
	}
}
