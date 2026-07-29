package io.metaloom.loom.pipeline.engine;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.STRUCT_JSON;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;
import static io.metaloom.loom.pipeline.engine.Payloads.element;
import static io.metaloom.loom.pipeline.engine.Payloads.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.TestDescriptors;
import io.metaloom.loom.pipeline.graph.ExecutionMode;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * One asset into many elements, and back into one workunit.
 *
 * <p>The scenario is the one the design is built around: node <strong>A</strong> turns an asset
 * into three texts, <strong>B</strong> and <strong>C</strong> each run once per text, and
 * <strong>D</strong> receives both branches recombined per source asset. There is no join node
 * and nothing for the pipeline author to configure — the barrier is the dependency check the
 * engine already performed, with "settled" redefined to mean "all of this node's elements
 * settled".</p>
 *
 * <p><strong>The assertion that matters most in every test here is that the run completes.</strong>
 * A mis-counted element width does not fail a run, it hangs one: the item waits forever for an
 * element that will never be dispatched, the source never gets its acknowledgement back, and the
 * only symptom is a scan that never finishes. Counting dispatches is how that becomes a test
 * failure instead of a support ticket.</p>
 */
public class PipelineRunEngineFanOutTest {

	private final PipelineGraphParser parser = new PipelineGraphParser(TestDescriptors.registry());

	private static JsonObject node(String id, String kind) {
		return new JsonObject().put("id", id).put("type", kind);
	}

	private static JsonObject edge(String from, String sourcePort, String to, String targetPort) {
		return new JsonObject().put("source", from).put("sourcePort", sourcePort)
			.put("target", to).put("targetPort", targetPort);
	}

	/**
	 * The §6.5 scenario: src → A, A fans out into B and C, both gathered by D.
	 *
	 * @param gatherBlocking whether D refuses to run when an upstream element failed
	 */
	private PipelineGraph scenario(boolean gatherBlocking) {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("A", "splitter"))
				.add(node("B", "worker"))
				.add(node("C", "scorer"))
				.add(node("D", "gatherer").put("blocking", gatherBlocking)))
			.put("edges", new JsonArray()
				.add(edge("src", "media", "A", "media"))
				.add(edge("A", "texts", "B", "text"))
				.add(edge("A", "texts", "C", "text"))
				.add(edge("B", "result", "D", "summaries"))
				.add(edge("C", "score", "D", "sentiments")));
		return parser.parse("fanout", definition, true, false, 0);
	}

	/** src → A → B → E, where B and E both run once per element of A's sequence. */
	private PipelineGraph perElementChain() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("A", "splitter"))
				.add(node("B", "worker"))
				.add(node("E", "worker")))
			.put("edges", new JsonArray()
				.add(edge("src", "media", "A", "media"))
				.add(edge("A", "texts", "B", "text"))
				.add(edge("B", "result", "E", "text")));
		return parser.parse("chain", definition, true, false, 0);
	}

	private static List<NodeTask> tasksFor(FakeNodeDispatcher dispatcher, String nodeId) {
		return dispatcher.dispatched().stream().filter(t -> t.getNodeId().equals(nodeId)).toList();
	}

	private static NodeTaskResult done(NodeTask task, String portId, PortPayload payload) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), task.getElementSeq(), 5,
			Map.of(portId, payload));
	}

	/** Settle the driver with a sequence of the given texts. */
	private static void driverEmits(PipelineRunEngine engine, FakeNodeDispatcher dispatcher, String item,
		String... texts) {
		NodeTask taskA = dispatcher.taskFor("A");
		engine.onNodeTaskResult(item, done(taskA, "texts", sequence(item, TEXT_PLAIN, (Object[]) texts)));
	}

	/** Answer one per-element task of the summary branch. */
	private static void completeB(PipelineRunEngine engine, String item, NodeTask task, int total) {
		engine.onNodeTaskResult(item,
			done(task, "result", element(item, task.getElementSeq(), total, TEXT_PLAIN, "b" + task.getElementSeq())));
	}

	/** Answer one per-element task of the scoring branch. */
	private static void completeC(PipelineRunEngine engine, String item, NodeTask task, int total) {
		engine.onNodeTaskResult(item, done(task, "score",
			element(item, task.getElementSeq(), total, STRUCT_JSON, Map.of("polarity", "p" + task.getElementSeq()))));
	}

	private static List<Integer> seqsOf(PortPayload payload) {
		return payload.getElements().stream().map(e -> e.getOrigin().getSeq()).toList();
	}

	// ------------------------------------------------------------------ the fan-out

	@Test
	void testTheDriverRunsOnceAndEachBranchRunsPerElement() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineGraph graph = scenario(true);
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		assertEquals(ExecutionMode.SINGLE, graph.getNode("A").getExecutionMode());
		assertEquals(ExecutionMode.PER_ELEMENT, graph.getNode("B").getExecutionMode());
		assertEquals(ExecutionMode.PER_ELEMENT, graph.getNode("C").getExecutionMode());
		assertEquals(ExecutionMode.SINGLE, graph.getNode("D").getExecutionMode(),
			"Two sequence inputs gather rather than iterate, so the join runs once");

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		assertEquals(1, tasksFor(dispatcher, "A").size(), "The driver itself is not fanned out");
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		assertEquals(1, tasksFor(dispatcher, "A").size(), "A settles once and is never re-dispatched");
		assertEquals(3, tasksFor(dispatcher, "B").size());
		assertEquals(3, tasksFor(dispatcher, "C").size());
		assertEquals(List.of(0, 1, 2), tasksFor(dispatcher, "B").stream().map(NodeTask::getElementSeq).toList());
		assertEquals(List.of(0, 1, 2), tasksFor(dispatcher, "C").stream().map(NodeTask::getElementSeq).toList());
	}

	@Test
	void testEachElementTaskCarriesOnlyItsOwnElement() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(true), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		for (NodeTask task : tasksFor(dispatcher, "B")) {
			PortPayload text = task.getInputs().get("text");
			assertNotNull(text, "A per-element task must be given its element");
			assertEquals(1, text.size(), "A ONE input takes exactly the element this execution is for");
			assertEquals("p" + task.getElementSeq(), text.single());
			assertEquals(task.getElementSeq(), text.getElements().get(0).getOrigin().getSeq());
			assertEquals(item, text.getElements().get(0).getOrigin().getItemId(),
				"The item is the origin - that is what lets the branches be recombined later");
			assertEquals("/media/a.mp4", task.getMedia().getPath(),
				"Every element reuses the origin asset's media reference");
		}
	}

	// ------------------------------------------------------------------ the gather

	@Test
	void testTheGatherWaitsForEveryElementOfBothBranches() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(true), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		List<NodeTask> bTasks = tasksFor(dispatcher, "B");
		List<NodeTask> cTasks = tasksFor(dispatcher, "C");

		// Answer five of the six executions, leaving one element of C outstanding.
		for (NodeTask task : bTasks) {
			completeB(engine, item, task, 3);
		}
		completeC(engine, item, cTasks.get(0), 3);
		completeC(engine, item, cTasks.get(1), 3);

		assertFalse(dispatcher.wasDispatched("D"),
			"One outstanding element of one branch is enough to hold the gather - otherwise the "
				+ "workunit would be assembled from a branch that is still running");

		completeC(engine, item, cTasks.get(2), 3);

		assertEquals(1, tasksFor(dispatcher, "D").size(), "The gather runs exactly once per origin asset");
	}

	@Test
	void testTheGatheredTaskCarriesBothBranchesSeqOrderedUnderOneOrigin() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(true), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");
		for (NodeTask task : tasksFor(dispatcher, "B")) {
			completeB(engine, item, task, 3);
		}
		for (NodeTask task : tasksFor(dispatcher, "C")) {
			completeC(engine, item, task, 3);
		}

		NodeTask gather = dispatcher.taskFor("D");
		PortPayload summaries = gather.getInputs().get("summaries");
		PortPayload sentiments = gather.getInputs().get("sentiments");

		assertEquals(List.of("b0", "b1", "b2"), summaries.values());
		assertEquals(3, sentiments.size());
		assertTrue(summaries.isMany() && sentiments.isMany(), "A gathered port is a sequence, not a single value");
		assertEquals(List.of(0, 1, 2), seqsOf(summaries), "Elements arrive in sequence order, not in reply order");
		assertEquals(List.of(0, 1, 2), seqsOf(sentiments));
		for (DataElement el : summaries.getElements()) {
			assertEquals(item, el.getOrigin().getItemId());
		}
		for (DataElement el : sentiments.getElements()) {
			assertEquals(item, el.getOrigin().getItemId());
		}

		engine.onNodeTaskResult(item, done(gather, "report", Payloads.payload(item, STRUCT_JSON, Map.of("ok", true))));
		assertTrue(engine.isComplete());
		assertEquals(1, engine.summary().getSuccessCount());
	}

	@Test
	void testResultsArrivingOutOfOrderStillGatherInSequenceOrder() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(true), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		// Workers are concurrent; nothing guarantees element 0 answers first.
		List<NodeTask> bTasks = tasksFor(dispatcher, "B");
		completeB(engine, item, bTasks.get(2), 3);
		completeB(engine, item, bTasks.get(0), 3);
		completeB(engine, item, bTasks.get(1), 3);
		for (NodeTask task : tasksFor(dispatcher, "C")) {
			completeC(engine, item, task, 3);
		}

		assertEquals(List.of("b0", "b1", "b2"), dispatcher.taskFor("D").getInputs().get("summaries").values());
	}

	// ------------------------------------------------------------------ empty sequence

	@Test
	void testAnEmptySequenceSkipsTheBranchesAndTheRunStillCompletes() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(true), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		// A found nothing to split. Zero elements is a legitimate answer, not a failure.
		driverEmits(engine, dispatcher, item);

		assertFalse(dispatcher.wasDispatched("B"), "There is no element to run for");
		assertFalse(dispatcher.wasDispatched("C"));
		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("B").getState());
		assertTrue(engine.getItem(item).getResults().get("B").getMessage().contains("empty"),
			"The reason must say why, or an empty sequence looks like a lost task");

		// The load-bearing half: a node that runs zero times must still *settle*, or the item
		// waits forever for an element that is never coming.
		NodeTask gather = dispatcher.taskFor("D");
		engine.onNodeTaskResult(item, done(gather, "report", Payloads.payload(item, STRUCT_JSON, Map.of())));
		assertTrue(engine.isComplete(), "An empty fan-out must not strand the run");
	}

	@Test
	void testAnEmptySequenceSkipsTheWholePerElementChain() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(perElementChain(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item);

		// 'E' is two hops from the driver and is not wired to it at all. It has to learn the
		// width from 'B' - reading it as anything other than empty would dispatch a task with
		// no input, or leave the item waiting for an element that does not exist.
		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("E").getState());
		assertTrue(dispatcher.dispatched().stream().allMatch(t -> t.getNodeId().equals("A")));
		assertTrue(engine.isComplete());
	}

	// ------------------------------------------------------------------ element failure

	@Test
	void testAFailedElementSkipsOnlyThatElementDownstream() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(perElementChain(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		List<NodeTask> bTasks = tasksFor(dispatcher, "B");
		completeB(engine, item, bTasks.get(0), 3);
		engine.onNodeTaskResult(item, NodeTaskResult.failed(bTasks.get(1).getTaskUuid(), "B", 1, 5, "boom", null));
		completeB(engine, item, bTasks.get(2), 3);

		List<NodeTask> eTasks = tasksFor(dispatcher, "E");
		assertEquals(List.of(0, 2), eTasks.stream().map(NodeTask::getElementSeq).toList(),
			"A sibling element's failure must not take out the whole row");
		assertEquals(NodeState.SKIPPED, engine.getItem(item).exec("E").getElementResults().get(1).getState());
		assertTrue(engine.getItem(item).exec("E").getElementResults().get(1).getMessage().contains("element 1"));
		assertEquals("b0", eTasks.get(0).getInputs().get("text").single());
		assertEquals("b2", eTasks.get(1).getInputs().get("text").single());

		// The skipped element never occupied a slot, so settling it must not release one -
		// a leaked slot wedges the run at its ceiling with nothing actually outstanding.
		assertEquals(2, engine.getInFlightCount(), "Only the two surviving elements are in flight");

		for (NodeTask task : eTasks) {
			engine.onNodeTaskResult(item, done(task, "result",
				element(item, task.getElementSeq(), 3, TEXT_PLAIN, "e" + task.getElementSeq())));
		}
		assertTrue(engine.isComplete(), "A partially failed fan-out must still reach a terminal state");
		assertEquals(1, engine.summary().getFailureCount(), "One failed element fails the item");
	}

	@Test
	void testABlockingGatherIsSkippedWhenAnyElementFailed() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(true), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		List<NodeTask> bTasks = tasksFor(dispatcher, "B");
		completeB(engine, item, bTasks.get(0), 3);
		engine.onNodeTaskResult(item, NodeTaskResult.failed(bTasks.get(1).getTaskUuid(), "B", 1, 5, "boom", null));
		completeB(engine, item, bTasks.get(2), 3);
		for (NodeTask task : tasksFor(dispatcher, "C")) {
			completeC(engine, item, task, 3);
		}

		// A node that gathers the sequence sees the branch as one unit, so any failure in it
		// stops a blocking consumer - exactly the whole-node rule it replaces.
		assertFalse(dispatcher.wasDispatched("D"));
		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("D").getState());
		assertTrue(engine.isComplete());
	}

	@Test
	void testANonBlockingGatherRunsWithTheSurvivors() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(false), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		List<NodeTask> bTasks = tasksFor(dispatcher, "B");
		completeB(engine, item, bTasks.get(0), 3);
		engine.onNodeTaskResult(item, NodeTaskResult.failed(bTasks.get(1).getTaskUuid(), "B", 1, 5, "boom", null));
		completeB(engine, item, bTasks.get(2), 3);
		for (NodeTask task : tasksFor(dispatcher, "C")) {
			completeC(engine, item, task, 3);
		}

		NodeTask gather = dispatcher.taskFor("D");
		assertEquals(List.of("b0", "b2"), gather.getInputs().get("summaries").values());
		// The gap is visible in the origin tags rather than papered over, so a node that cares
		// can tell it received two of three.
		assertEquals(List.of(0, 2), seqsOf(gather.getInputs().get("summaries")));
		assertEquals(3, gather.getInputs().get("sentiments").size(), "The healthy branch is untouched");

		engine.onNodeTaskResult(item, done(gather, "report", Payloads.payload(item, STRUCT_JSON, Map.of())));
		assertTrue(engine.isComplete());
	}

	@Test
	void testAFailedElementIsRetriedAsThatElement() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("A", "splitter"))
				.add(node("B", "worker").put("options", new JsonObject().put("retryFailed", true))))
			.put("edges", new JsonArray()
				.add(edge("src", "media", "A", "media"))
				.add(edge("A", "texts", "B", "text")));
		PipelineRunEngine engine = new PipelineRunEngine(
			parser.parse("retry", definition, true, false, 0), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		driverEmits(engine, dispatcher, item, "p0", "p1", "p2");

		List<NodeTask> first = tasksFor(dispatcher, "B");
		engine.onNodeTaskResult(item, NodeTaskResult.failed(first.get(1).getTaskUuid(), "B", 1, 5, "transient", null));

		// The retry budget belongs to the execution, not to the node. Booking it against
		// element 0 would spend a budget that is not this element's and, when element 0 had
		// already settled, park the retry on a slot nothing will ever dispatch - leaving the
		// failed element neither retried nor settled.
		List<NodeTask> retried = tasksFor(dispatcher, "B").subList(3, tasksFor(dispatcher, "B").size());
		assertEquals(1, retried.size(), "Exactly the failed element is attempted again");
		assertEquals(1, retried.get(0).getElementSeq());
		assertEquals("p1", retried.get(0).getInputs().get("text").single());

		completeB(engine, item, first.get(0), 3);
		completeB(engine, item, first.get(2), 3);
		completeB(engine, item, retried.get(0), 3);
		assertTrue(engine.isComplete(), "A retried element must still settle the item");
	}

	// ------------------------------------------------------------------ several items

	@Test
	void testTwoItemsFanOutIndependently() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(scenario(true), dispatcher, UUID.randomUUID());

		engine.start();
		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		String b = engine.onItemDiscovered(MediaRef.of("/media/b.mp4"));
		engine.onSourceComplete(2);

		// Different widths, so a shared counter would show up immediately.
		NodeTask aDriver = dispatcher.dispatched().stream()
			.filter(t -> t.getNodeId().equals("A") && t.getItemId().equals(a)).findFirst().orElseThrow();
		NodeTask bDriver = dispatcher.dispatched().stream()
			.filter(t -> t.getNodeId().equals("A") && t.getItemId().equals(b)).findFirst().orElseThrow();
		engine.onNodeTaskResult(a, done(aDriver, "texts", sequence(a, TEXT_PLAIN, "a0", "a1")));
		engine.onNodeTaskResult(b, done(bDriver, "texts", sequence(b, TEXT_PLAIN, "b0", "b1", "b2")));

		assertEquals(2, tasksFor(dispatcher, "B").stream().filter(t -> t.getItemId().equals(a)).count());
		assertEquals(3, tasksFor(dispatcher, "B").stream().filter(t -> t.getItemId().equals(b)).count());
		// The item is the origin, so elements never leak between assets.
		for (NodeTask task : tasksFor(dispatcher, "B")) {
			assertEquals(task.getItemId(),
				task.getInputs().get("text").getElements().get(0).getOrigin().getItemId());
		}
	}
}
