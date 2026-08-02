package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.CONTROL_FILTER;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.DETECTION_FACE;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_SHA512;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.STRUCT_DEPTHMAP;
import static io.metaloom.loom.pipeline.engine.Payloads.outputs;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
 * Evaluation-model tests for {@link PipelineRunEngine}.
 *
 * <p>These pin the semantics that the Cortex executor implements today, so that
 * moving the decision to Loom does not quietly change behaviour: blocking-dependency
 * skips, non-cascading skips, direct-only filter branches, and dry-run.</p>
 */
public class PipelineRunEngineTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	// src -> hash -> thumb
	private PipelineGraph linearGraph(boolean dryRun) {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media"))
				.add(new JsonObject().put("source", "hash").put("sourcePort", "hash").put("target", "thumb").put("targetPort", "media")));
		return parser.parse("linear", definition, true, dryRun, 0);
	}

	private static MediaRef media(String path) {
		return MediaRef.of(path);
	}

	private static NodeTaskResult ok(NodeTask task, Map<String, PortPayload> outputs) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, outputs);
	}

	@Test
	void testHappyPathRunsEveryNodeInOrder() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher, UUID.randomUUID());
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		// The source result is synthesised, so only 'hash' is dispatched at first.
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds(),
			"Only the node whose dependencies are satisfied may be dispatched");
		assertFalse(engine.isComplete());

		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "abc")));
		assertEquals(List.of("hash", "thumb"), dispatcher.dispatchedNodeIds(),
			"Completing 'hash' must unblock 'thumb'");

		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("thumb"), Map.of()));

		assertTrue(engine.isComplete());
		assertNotNull(summary.get(), "Completion listener must fire exactly once");
		assertEquals(1, summary.get().getMediaCount());
		assertEquals(1, summary.get().getSuccessCount());
		assertEquals(0, summary.get().getFailureCount());
		assertEquals("SUCCESS", summary.get().getStatus());
	}

	@Test
	void testSourceNodeIsNotDispatched() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));

		assertFalse(dispatcher.wasDispatched("src"),
			"The source result is derivable from the discovered item; round-tripping it "
				+ "to a worker would cost a network hop per item for nothing");
		NodeTaskResult sourceResult = engine.getItem(item).getResults().get("src");
		assertEquals(NodeState.COMPLETED, sourceResult.getState());
		assertEquals("/media/a.mp4", sourceResult.output(PipelineRunEngine.SOURCE_MEDIA_PORT).single(),
			"The source's media port is what every downstream media input binds to");
	}

	@Test
	void testUpstreamOutputsArePassedDownstream() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), outputs("hash", HASH_SHA512, "deadbeef")));

		NodeTask thumbTask = dispatcher.taskFor("thumb");
		// Keyed by the *receiving* node's own port id, not by whatever the author called the
		// node upstream - renaming 'hash' in the editor can no longer starve this lookup.
		assertEquals("deadbeef", thumbTask.getInputs().get("media").single(),
			"A node must see the outputs of its dependencies on its own input port");
	}

	@Test
	void testFailedDependencySkipsBlockingChild() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher);
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		NodeTask hashTask = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(item, NodeTaskResult.failed(hashTask.getTaskUuid(), "hash", 3, "boom"));

		assertFalse(dispatcher.wasDispatched("thumb"), "A blocking node must not run after its dependency failed");
		NodeTaskResult thumb = engine.getItem(item).getResults().get("thumb");
		assertEquals(NodeState.SKIPPED, thumb.getState());
		assertTrue(thumb.getMessage().contains("hash"), "Skip reason should name the failed dependency");

		assertTrue(engine.isComplete());
		assertEquals(1, summary.get().getFailureCount());
		assertEquals("FAILED", summary.get().getStatus());
	}

	@Test
	void testFailedDependencyDoesNotSkipNonBlockingChild() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail").put("blocking", false)))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media"))
				.add(new JsonObject().put("source", "hash").put("sourcePort", "hash").put("target", "thumb").put("targetPort", "media")));

		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("nb", definition, true, false, 0), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, NodeTaskResult.failed(dispatcher.taskFor("hash").getTaskUuid(), "hash", 3, "boom"));

		assertTrue(dispatcher.wasDispatched("thumb"),
			"Blocking is a property of the dependent node - a non-blocking node runs anyway");
	}

	@Test
	void testSkipDoesNotCascade() {
		// src -> a(blocking) -> b : 'a' is skipped by a filter branch, and 'b' must
		// still run, because SKIPPED is not FAILED.
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "flt").put("type", "filter"))
				.add(new JsonObject().put("id", "a").put("type", "sha512"))
				.add(new JsonObject().put("id", "b").put("type", "md5")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "flt").put("targetPort", "media"))
				.add(new JsonObject().put("source", "flt").put("sourcePort", "passed").put("target", "a").put("targetPort", "media").put("branch", "PASS"))
				.add(new JsonObject().put("source", "a").put("sourcePort", "hash").put("target", "b").put("targetPort", "media")));

		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("skip", definition, true, false, 0), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.txt"));
		// Filter rejects, so 'a' is skipped.
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("flt"), outputs("passed", CONTROL_FILTER, false)));

		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("a").getState());
		assertTrue(dispatcher.wasDispatched("b"),
			"Filter skipping is not transitive: 'b' has no conditional dependency on the filter, "
				+ "and a SKIPPED parent does not cascade");
	}

	@Test
	void testFilterBranchRoutesPassAndReject() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "flt").put("type", "filter"))
				.add(new JsonObject().put("id", "keep").put("type", "sha256"))
				.add(new JsonObject().put("id", "drop").put("type", "md5")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "flt").put("targetPort", "media"))
				.add(new JsonObject().put("source", "flt").put("sourcePort", "passed").put("target", "keep").put("targetPort", "media").put("branch", "PASS"))
				.add(new JsonObject().put("source", "flt").put("sourcePort", "passed").put("target", "drop").put("targetPort", "media").put("branch", "REJECT")));

		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("branch", definition, true, false, 0), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.jpg"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("flt"), outputs("passed", CONTROL_FILTER, true)));

		assertTrue(dispatcher.wasDispatched("keep"), "PASS branch must run when the filter passed");
		assertFalse(dispatcher.wasDispatched("drop"), "REJECT branch must not run when the filter passed");
		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("drop").getState());
	}

	@Test
	void testStringifiedFilterVerdictIsTolerated() {
		// Cortex's persistent caches stringify values, so a boolean can arrive as "true".
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "flt").put("type", "filter"))
				.add(new JsonObject().put("id", "keep").put("type", "sha256")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "flt").put("targetPort", "media"))
				.add(new JsonObject().put("source", "flt").put("sourcePort", "passed").put("target", "keep").put("targetPort", "media").put("branch", "PASS")));

		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("strbool", definition, true, false, 0), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.jpg"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("flt"), outputs("passed", CONTROL_FILTER, "true")));

		assertTrue(dispatcher.wasDispatched("keep"), "A stringified 'true' must route the same as a boolean");
	}

	@Test
	void testDryRunSkipsEverythingAndDispatchesNothing() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(true), dispatcher);
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		assertTrue(dispatcher.dispatched().isEmpty(), "A dry run must not dispatch any work");
		engine.getItem(item).getResults().values()
			.forEach(r -> assertEquals(NodeState.SKIPPED, r.getState()));
		assertTrue(engine.isComplete());
		assertEquals(1, summary.get().getSkippedCount(),
			"An item where nothing ran counts as skipped, not as a success");
		assertEquals(0, summary.get().getSuccessCount());
	}

	@Test
	void testDisabledPipelineCompletesWithoutWork() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true)));
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("off", definition, false, false, 0), dispatcher);
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();

		assertTrue(engine.isComplete());
		assertEquals(0, summary.get().getMediaCount());
		assertEquals("SUCCESS", summary.get().getStatus());
	}

	@Test
	void testUndispatchableNodeFailsRatherThanStallingTheRun() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher().rejectKind("sha512");
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher);
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		NodeTaskResult hash = engine.getItem(item).getResults().get("hash");
		assertEquals(NodeState.FAILED, hash.getState(),
			"With no worker for the kind the node must fail, not wait forever for a result "
				+ "that will never arrive");
		assertTrue(hash.getMessage().contains("sha512"));
		assertTrue(engine.isComplete(), "The run must reach a terminal state");
		assertEquals("FAILED", summary.get().getStatus());
	}

	@Test
	void testEveryNodeSettlesWhenNoWorkerAcceptsAnything() {
		// Exercises the synchronous-settlement path: nothing is dispatchable, so every
		// node must settle within the same pass rather than leaving the run hanging.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher().rejectAll();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher);
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		assertTrue(engine.isComplete(), "The run must terminate even when nothing can be dispatched");
		engine.getItem(item).getResults().values()
			.forEach(r -> assertTrue(r.getState().isTerminal(), "Every node must reach a terminal state"));
		assertEquals(NodeState.FAILED, engine.getItem(item).getResults().get("hash").getState());
		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("thumb").getState(),
			"'thumb' blocks on a failed dependency, so it is skipped rather than dispatched");
		assertEquals("FAILED", summary.get().getStatus());
	}

	@Test
	void testMultipleItemsProgressIndependently() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher);
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);

		engine.start();
		String a = engine.onItemDiscovered(media("/media/a.mp4"));
		String b = engine.onItemDiscovered(media("/media/b.mp4"));
		engine.onSourceComplete(2);

		List<NodeTask> hashTasks = dispatcher.dispatched().stream()
			.filter(t -> t.getNodeId().equals("hash")).toList();
		assertEquals(2, hashTasks.size(), "Each item gets its own task");

		// Fail one item, complete the other.
		NodeTask taskA = hashTasks.stream().filter(t -> t.getItemId().equals(a)).findFirst().orElseThrow();
		NodeTask taskB = hashTasks.stream().filter(t -> t.getItemId().equals(b)).findFirst().orElseThrow();
		engine.onNodeTaskResult(a, NodeTaskResult.failed(taskA.getTaskUuid(), "hash", 1, "boom"));
		engine.onNodeTaskResult(b, ok(taskB, outputs("hash", HASH_SHA512, "abc")));

		NodeTask thumbB = dispatcher.dispatched().stream()
			.filter(t -> t.getNodeId().equals("thumb")).findFirst().orElseThrow();
		assertEquals(b, thumbB.getItemId(), "Only the healthy item proceeds");
		engine.onNodeTaskResult(b, ok(thumbB, Map.of()));

		assertTrue(engine.isComplete());
		assertEquals(2, summary.get().getMediaCount());
		assertEquals(1, summary.get().getSuccessCount());
		assertEquals(1, summary.get().getFailureCount());
		assertEquals("PARTIAL", summary.get().getStatus(),
			"Some but not all items failing is a partial run");
	}

	@Test
	void testDiamondWaitsForBothDependencies() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "left").put("type", "depthmap"))
				.add(new JsonObject().put("id", "right").put("type", "facedetect"))
				.add(new JsonObject().put("id", "join").put("type", "scene-layout")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "left").put("targetPort", "media"))
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "right").put("targetPort", "image"))
				.add(new JsonObject().put("source", "left").put("sourcePort", "meta").put("target", "join").put("targetPort", "depth"))
				.add(new JsonObject().put("source", "right").put("sourcePort", "detections").put("target", "join").put("targetPort", "detections")));

		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("diamond", definition, true, false, 0), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));

		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("left"), outputs("meta", STRUCT_DEPTHMAP, Map.of("near", 1))));
		assertFalse(dispatcher.wasDispatched("join"), "A join must wait for every dependency");

		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("right"), outputs("detections", DETECTION_FACE, Map.of("box", "0,0,10,10"))));
		assertTrue(dispatcher.wasDispatched("join"));

		NodeTask joinTask = dispatcher.taskFor("join");
		assertEquals(2, joinTask.getInputs().size(), "A join sees both upstream results");
		assertTrue(joinTask.getInputs().containsKey("depth"));
		assertTrue(joinTask.getInputs().containsKey("detections"));
	}

	@Test
	void testDuplicateResultIsIgnored() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(false), dispatcher);

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		NodeTask hashTask = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(item, ok(hashTask, outputs("hash", HASH_SHA512, "first")));
		engine.onNodeTaskResult(item, ok(hashTask, outputs("hash", HASH_SHA512, "second")));

		assertEquals("first", engine.getItem(item).getResults().get("hash").output("hash").single(),
			"Duplicate delivery must not overwrite a settled result - it becomes routine once retries exist");
	}
}
