package io.metaloom.loom.pipeline.engine;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_SHA512;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.STRUCT_JSON;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;
import static io.metaloom.loom.pipeline.engine.Payloads.element;
import static io.metaloom.loom.pipeline.engine.Payloads.outputs;
import static io.metaloom.loom.pipeline.engine.Payloads.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.TestDescriptors;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Breakpoints: holding a node's output back from its dependents so a person can look at it.
 *
 * <p>
 * The behaviour under test is deliberately narrow. A breakpoint stops the node's
 * <em>dependents</em>, never the node itself — by the time it holds, the execution has already run
 * and its result is recorded, because a result you cannot see is not worth stopping for. So the
 * assertions come in pairs throughout: the breakpointed node <strong>did</strong> settle, and what
 * comes after it <strong>did not</strong> dispatch.
 * </p>
 *
 * <p>
 * The failure mode to guard against is the same one the fan-out tests warn about: a mistake here
 * does not fail a run, it hangs one. A hold that is never released, or a release that does not
 * re-enter the scheduler, leaves the run sitting with outstanding work and no symptom other than
 * silence. Every test therefore ends by releasing and asserting the run actually moved.
 * </p>
 */
public class PipelineRunEngineBreakpointTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();
	private final PipelineGraphParser fanOutParser = new PipelineGraphParser(TestDescriptors.registry());

	private static JsonObject node(String id, String kind) {
		return new JsonObject().put("id", id).put("type", kind);
	}

	private static JsonObject edge(String from, String sourcePort, String to, String targetPort) {
		return new JsonObject().put("source", from).put("sourcePort", sourcePort)
			.put("target", to).put("targetPort", targetPort);
	}

	/** src -> hash -> thumb. */
	private PipelineGraph linearGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "filesystem-source").put("source", true))
				.add(node("hash", "sha512"))
				.add(node("thumb", "thumbnail")))
			.put("edges", new JsonArray()
				.add(edge("src", "media", "hash", "media"))
				.add(edge("hash", "hash", "thumb", "media")));
		return parser.parse("linear", definition, true, false, 0);
	}

	/** src -> A (fans out) -> B, one B per element. */
	private PipelineGraph fanOutGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("A", "splitter"))
				.add(node("B", "worker")))
			.put("edges", new JsonArray()
				.add(edge("src", "media", "A", "media"))
				.add(edge("A", "texts", "B", "text")));
		return fanOutParser.parse("fanout", definition, true, false, 0);
	}

	private static MediaRef media(String path) {
		return MediaRef.of(path);
	}

	private static NodeTaskResult ok(NodeTask task, Map<String, PortPayload> outputs) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, outputs);
	}

	private static Map<String, PortPayload> hash(String value) {
		return outputs("hash", HASH_SHA512, value);
	}

	@Test
	@DisplayName("a breakpoint holds the dependents and not the node itself")
	void testBreakpointHoldsDependentsNotTheNode() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		assertEquals(List.of("hash"), dispatcher.dispatchedNodeIds(),
			"An armed breakpoint must not stop the node from running - there would be nothing to look at");

		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));

		// The half that makes a breakpoint worth having: the result exists and is readable.
		assertEquals(NodeState.COMPLETED, engine.getItem(item).getResults().get("hash").getState());
		assertEquals("abc", engine.getItem(item).getResults().get("hash").getOutputs()
			.get("hash").getElements().get(0).getValue());
		// The other half: nothing moved on.
		assertFalse(dispatcher.wasDispatched("thumb"), "A held node must not release its dependents");
		assertEquals(1, engine.heldCount());
	}

	@Test
	@DisplayName("releasing a node dispatches what it was holding back")
	void testReleaseLetsTheRunContinue() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));
		assertFalse(dispatcher.wasDispatched("thumb"));

		assertEquals(1, engine.releaseNode("hash"));

		assertTrue(dispatcher.wasDispatched("thumb"), "Releasing must re-enter the scheduler, not merely clear a flag");
		assertEquals(0, engine.heldCount());
	}

	@Test
	@DisplayName("releasing leaves the breakpoint armed, so the next item stops too")
	void testBreakpointStaysArmedAfterRelease() {
		// The distinction between a breakpoint and a one-shot. An operator who wants the run to
		// stop being interrupted disarms it; releasing just means "carry on with this one".
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String first = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(first, ok(dispatcher.taskFor("hash"), hash("abc")));
		engine.releaseNode("hash");
		assertEquals(java.util.Set.of("hash"), engine.getBreakpoints());

		String second = engine.onItemDiscovered(media("/media/b.mp4"));
		engine.onNodeTaskResult(second, ok(dispatcher.taskFor("hash", second), hash("def")));

		assertEquals(1, engine.heldCount(), "The second item must hit the same breakpoint");
		assertEquals(second, engine.heldExecutions().get(0).itemId());
	}

	@Test
	@DisplayName("disarming a breakpoint releases whatever it was holding")
	void testDisarmingReleases() {
		// Otherwise clearing a breakpoint would strand the run: the hold would outlive the reason
		// for it and nothing left in the UI would explain why the run had stopped.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));
		assertEquals(1, engine.heldCount());

		engine.setBreakpoints(List.of());

		assertEquals(0, engine.heldCount());
		assertTrue(dispatcher.wasDispatched("thumb"), "Disarming must let the run continue");
	}

	@Test
	@DisplayName("stepOne releases exactly one execution")
	void testStepReleasesExactlyOne() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String a = engine.onItemDiscovered(media("/media/a.mp4"));
		String b = engine.onItemDiscovered(media("/media/b.mp4"));
		engine.onNodeTaskResult(a, ok(dispatcher.taskFor("hash", a), hash("abc")));
		engine.onNodeTaskResult(b, ok(dispatcher.taskFor("hash", b), hash("def")));
		assertEquals(2, engine.heldCount());

		assertTrue(engine.stepOne());

		assertEquals(1, engine.heldCount(), "A step releases one execution, not one node");
		// Oldest first, in item discovery order: repeated stepping walks a lineage forward rather
		// than jumping between items.
		assertEquals(b, engine.heldExecutions().get(0).itemId());

		assertTrue(engine.stepOne());
		assertEquals(0, engine.heldCount());
		assertFalse(engine.stepOne(), "Stepping with nothing held reports that it did nothing");
	}

	@Test
	@DisplayName("a fanned-out node holds each element, and every one must be released to gather")
	void testFanOutHoldsPerElement() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(fanOutGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("B"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("A"),
			outputs("texts", sequence(item, TEXT_PLAIN, "one", "two", "three"))));

		// B runs three times, and each execution is held independently.
		List<NodeTask> bTasks = dispatcher.tasksFor("B");
		assertEquals(3, bTasks.size());
		for (NodeTask task : bTasks) {
			engine.onNodeTaskResult(item, NodeTaskResult.completed(task.getTaskUuid(), "B", task.getElementSeq(), 1,
				outputs("result", element(item, task.getElementSeq(), 3, STRUCT_JSON, "r" + task.getElementSeq()))));
		}
		assertEquals(3, engine.heldCount(), "Each element of a fanned-out node is held on its own");

		// Stepping twice is not enough: the gather consumes the whole sequence, so it may not
		// start until the last element has been let through.
		engine.stepOne();
		engine.stepOne();
		assertEquals(1, engine.heldCount());

		engine.stepOne();
		assertEquals(0, engine.heldCount());
	}

	@Test
	@DisplayName("a held run does not complete, and completes as soon as it is released")
	void testHeldRunDoesNotAutoComplete() {
		// The easy-to-miss one. A breakpoint on the last node of the graph holds nothing back -
		// there are no dependents - so without an explicit guard every item would look complete
		// and the run would close out underneath the person reading the result.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		AtomicReference<RunSummary> summary = new AtomicReference<>();
		engine.onCompletion(summary::set);
		engine.setBreakpoints(List.of("thumb"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("thumb"), Map.of()));

		assertFalse(engine.isComplete(), "A run holding at a breakpoint has outstanding work by definition");
		assertEquals(null, summary.get(), "A held run must not fire completion listeners");

		engine.releaseNode("thumb");

		assertTrue(engine.isComplete(), "Releasing the last hold lets the run close out");
		assertNotNull(summary.get());
	}

	@Test
	@DisplayName("a held run withholds the source acknowledgement")
	void testHeldRunHoldsTheSource() {
		// Without this a breakpoint would stop each item's graph but not the scan, so a run over
		// 100 000 files would enumerate all of them and hold 100 000 executions while somebody
		// reads the first one.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(100);
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));
		assertFalse(engine.isAtCapacity(), "Precondition: there is plenty of room");

		AtomicInteger released = new AtomicInteger();
		engine.whenCapacityAvailable(released::incrementAndGet);
		assertEquals(0, released.get(), "A held run parks the source even with capacity to spare");

		engine.releaseNode("hash");
		assertEquals(1, released.get(), "Releasing the last hold releases the source");
	}

	@Test
	@DisplayName("cancelling a held run releases the source rather than stranding it")
	void testCancelWhileHeld() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));
		AtomicInteger released = new AtomicInteger();
		engine.whenCapacityAvailable(released::incrementAndGet);
		assertEquals(0, released.get());

		engine.cancel();

		assertTrue(engine.isComplete());
		assertEquals(1, released.get(), "Cancelling a held run must not strand the source");
	}

	@Test
	@DisplayName("a skipped or failed execution is not held")
	void testOnlyCompletedExecutionsAreHeld() {
		// Holding a skip would stop the run at a node with nothing to show, and holding a failure
		// would suppress the skip cascade that explains why the rest of the graph did not run -
		// which is exactly the explanation an operator stopped to read.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, NodeTaskResult.failed(dispatcher.taskFor("hash").getTaskUuid(), "hash", 1, "boom"));

		assertEquals(0, engine.heldCount(), "A failed execution is not held");
		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("thumb").getState(),
			"The skip cascade still runs, so the run explains itself");
	}

	@Test
	@DisplayName("the breakpoint listener reports each hold and each release")
	void testListenerReportsHoldAndRelease() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		List<String> events = new ArrayList<>();
		engine.onBreakpoint((itemId, mediaPath, nodeId, seq, held) -> events.add((held ? "held " : "released ") + nodeId + "#" + seq));
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));
		engine.releaseNode("hash");

		assertEquals(List.of("held hash#0", "released hash#0"), events);
	}

	@Test
	@DisplayName("a listener that throws does not derail the run")
	void testListenerFailureIsContained() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		engine.onBreakpoint((itemId, mediaPath, nodeId, seq, held) -> {
			throw new IllegalStateException("observer is broken");
		});
		engine.setBreakpoints(List.of("hash"));

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("abc")));

		assertEquals(1, engine.heldCount(), "The hold is applied even though the observer threw");
		engine.releaseNode("hash");
		assertTrue(dispatcher.wasDispatched("thumb"));
	}

	@Test
	@DisplayName("a segment containing a breakpoint falls back to per-node dispatch")
	void testSegmentWithBreakpointIsNotFused() {
		// A fused segment runs end to end inside one worker and only the last node's outputs come
		// back, so a breakpoint inside it would have nothing to hold and nothing to show. Paying
		// for a round trip per node is the trade the operator made by setting it.
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(segmentGraph(), dispatcher, UUID.randomUUID());
		engine.setBreakpoints(List.of("b"));

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4"));

		assertTrue(dispatcher.segmentTasks.isEmpty(), "A segment with a breakpoint in it must not be fused");
		assertEquals(List.of("a"), dispatcher.nodeTasks.stream().map(NodeTask::getNodeId).toList());
	}

	@Test
	@DisplayName("the same segment is fused again once the breakpoint is cleared")
	void testSegmentIsFusedWithoutABreakpoint() {
		// The control for the test above: the fallback must be caused by the breakpoint and not by
		// something about the graph.
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(segmentGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(media("/media/a.mp4"));

		assertEquals(1, dispatcher.segmentTasks.size());
		assertTrue(dispatcher.nodeTasks.isEmpty());
	}

	/** src -> a -> b, with a and b sharing an affinity group so they fuse into one segment. */
	private PipelineGraph segmentGraph() {
		JsonArray nodes = new JsonArray()
			.add(node("src", "filesystem-source").put("source", true))
			.add(node("a", "video-decode").put("affinity", "video"))
			.add(node("b", "keyframe").put("affinity", "video"));
		JsonArray edges = new JsonArray()
			.add(edge("src", "media", "a", "media"))
			.add(edge("a", "video", "b", "frames"));
		return parser.parse("seg", new JsonObject().put("nodes", nodes).put("edges", edges), true, false, 0);
	}

	/** Records both dispatch kinds so the choice between them is observable. */
	private static class SegmentAwareDispatcher implements NodeDispatcher {

		final List<NodeTask> nodeTasks = new ArrayList<>();
		final List<SegmentTask> segmentTasks = new ArrayList<>();

		@Override
		public String dispatch(NodeTask task) {
			nodeTasks.add(task);
			return "worker";
		}

		@Override
		public String dispatch(SegmentTask task) {
			segmentTasks.add(task);
			return "worker";
		}
	}
}
