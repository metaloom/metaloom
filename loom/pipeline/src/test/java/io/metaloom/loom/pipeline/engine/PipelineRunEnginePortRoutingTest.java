package io.metaloom.loom.pipeline.engine;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.SCALAR_STRING;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;
import static io.metaloom.loom.pipeline.engine.Payloads.element;
import static io.metaloom.loom.pipeline.engine.Payloads.outputs;
import static io.metaloom.loom.pipeline.engine.Payloads.payload;
import static io.metaloom.loom.pipeline.engine.Payloads.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
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
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The port <em>is</em> the branch.
 *
 * <p>
 * A node declaring a {@link io.metaloom.loom.nodes.spec.PortSpec#isSelective() selective} output
 * says "this port carries data for some items and not others". The engine turns that into routing:
 * a consumer wired only to ports the producer did not write for an item is skipped for that item.
 * Drawing the edge is the whole configuration — there is no {@code branch} attribute involved, and
 * an N-way split needs no new vocabulary, just N ports.
 * </p>
 *
 * <p>
 * <strong>This test class must parse with a descriptor registry.</strong> Its siblings deliberately
 * do not, and {@code PortGraphAnalyzer} returns early without one — which is exactly why adding
 * routing changed none of their assertions. Nothing here would be exercised without the registry.
 * </p>
 */
public class PipelineRunEnginePortRoutingTest {

	private final PipelineGraphParser parser = new PipelineGraphParser(TestDescriptors.registry());

	private static JsonObject node(String id, String kind) {
		return new JsonObject().put("id", id).put("type", kind);
	}

	private static JsonObject edge(String from, String sourcePort, String to, String targetPort) {
		return new JsonObject().put("source", from).put("sourcePort", sourcePort)
			.put("target", to).put("targetPort", targetPort);
	}

	private PipelineGraph parse(String name, JsonArray nodes, JsonArray edges) {
		return parser.parse(name, new JsonObject().put("nodes", nodes).put("edges", edges), true, false, 0);
	}

	private static MediaRef media(String path) {
		return MediaRef.of(path);
	}

	private static NodeTaskResult ok(NodeTask task, Map<String, PortPayload> outputs) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, outputs);
	}

	private static Map<String, PortPayload> ports(String portA, PortPayload a, String portB, PortPayload b) {
		Map<String, PortPayload> out = new LinkedHashMap<>();
		out.put(portA, a);
		out.put(portB, b);
		return out;
	}

	/**
	 * src → r(router); r.a → da, r.b → db, r.label → note.
	 *
	 * <p>
	 * {@code note} is wired to the non-selective {@code label} port, so it must run whichever branch
	 * the item took. That is the escape hatch for "I want the decision, not the item".
	 * </p>
	 */
	private PipelineGraph twoWay() {
		return parse("two-way",
			new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("r", "router"))
				.add(node("da", "describe"))
				.add(node("db", "describe"))
				.add(node("note", "noticer")),
			new JsonArray()
				.add(edge("src", "media", "r", "media"))
				.add(edge("r", "a", "da", "media"))
				.add(edge("r", "b", "db", "media"))
				.add(edge("r", "label", "note", "label")));
	}

	@Test
	void testOnlyTheBranchThatCarriedDataRuns() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(twoWay(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		// The router writes 'a' and the non-selective 'label'. 'b' is simply not in the bag.
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("r"),
			ports("a", payload(MEDIA_ANY, "/media/a.mp4"), "label", payload(SCALAR_STRING, "de"))));

		assertTrue(dispatcher.wasDispatched("da"), "the branch that carried the item must run");
		assertFalse(dispatcher.wasDispatched("db"),
			"nothing was written to 'b', so the item never went down that branch");
		assertTrue(dispatcher.wasDispatched("note"),
			"'label' is not selective - a node consuming the decision runs for every item");

		NodeTaskResult skipped = engine.getItem(item).getResults().get("db");
		assertEquals(NodeState.SKIPPED, skipped.getState());
		assertTrue(skipped.getMessage().contains("No routed output of dependency r"), skipped.getMessage());
	}

	/**
	 * The same graph, the other way. Worth its own case: an asymmetry here would mean the first
	 * declared port was winning by position rather than by what was emitted.
	 */
	@Test
	void testTheOtherBranchRoutesJustTheSame() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(twoWay(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("r"),
			ports("b", payload(MEDIA_ANY, "/media/a.mp4"), "label", payload(SCALAR_STRING, "en"))));

		assertFalse(dispatcher.wasDispatched("da"));
		assertTrue(dispatcher.wasDispatched("db"));
		assertTrue(dispatcher.wasDispatched("note"));
	}

	/**
	 * A router that wrote nothing at all closes both branches — and the run must still complete
	 * rather than waiting for tasks that will never be dispatched.
	 *
	 * <p>
	 * The node on the <em>non-selective</em> port still runs, with an empty input. That asymmetry is
	 * the whole design: leaving a declared output unwritten has always been normal and harmless
	 * ({@code facedetect} finds no faces), and only a port that declares itself selective is allowed
	 * to change that. Were the rule applied to every port, every graph in the repository would
	 * silently change behaviour.
	 * </p>
	 */
	@Test
	void testAnEmptyResultClosesTheSelectiveBranchesOnly() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(twoWay(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("r"), Map.of()));

		assertFalse(dispatcher.wasDispatched("da"));
		assertFalse(dispatcher.wasDispatched("db"));
		assertTrue(dispatcher.wasDispatched("note"),
			"'label' is not selective, so an unwritten value is an absent input - not a closed branch");
		assertTrue(dispatcher.taskFor("note").getInputs().isEmpty(),
			"and it arrives with nothing on that port, exactly as before port routing existed");

		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("note"), Map.of()));
		assertTrue(engine.isComplete(), "a run whose branches all closed must still finish");
	}

	/**
	 * Selectivity is inherited. If the branch did not fire, the node on it is skipped, so its own
	 * outputs are empty and <em>its</em> consumers must skip too.
	 *
	 * <p>
	 * This is the defect the older {@code FilterBranch} mechanism still has — filter skipping is
	 * documented as non-transitive there, which leaves a grandchild running with empty inputs.
	 * </p>
	 */
	@Test
	void testSkippingCascadesDownTheBranch() {
		PipelineGraph graph = parse("cascade",
			new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("r", "router"))
				.add(node("da", "describe"))
				.add(node("w", "worker")),
			new JsonArray()
				.add(edge("src", "media", "r", "media"))
				.add(edge("r", "a", "da", "media"))
				.add(edge("da", "text", "w", "text")));

		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("r"), outputs("b", payload(MEDIA_ANY, "/media/a.mp4"))));

		assertFalse(dispatcher.wasDispatched("da"), "the 'a' branch did not fire");
		assertFalse(dispatcher.wasDispatched("w"),
			"'w' is two hops from the router; a one-hop rule would run it here with empty inputs");
		assertEquals(NodeState.SKIPPED, engine.getItem(item).getResults().get("w").getState());
		assertTrue(engine.isComplete());
	}

	/**
	 * Routing never overrides {@code blocking: false}. A non-blocking consumer of a failed producer
	 * is supposed to run and see the failure in its inputs; skipping it here because the failed node
	 * emitted nothing would quietly reverse that.
	 */
	@Test
	void testANonBlockingConsumerOfAFailedRouterStillRuns() {
		PipelineGraph graph = parse("failed",
			new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("r", "router"))
				.add(node("da", "describe").put("blocking", false))
				.add(node("db", "describe")),
			new JsonArray()
				.add(edge("src", "media", "r", "media"))
				.add(edge("r", "a", "da", "media"))
				.add(edge("r", "b", "db", "media")));

		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		NodeTask task = dispatcher.taskFor("r");
		engine.onNodeTaskResult(item, NodeTaskResult.failed(task.getTaskUuid(), task.getNodeId(), 5, "boom"));

		assertTrue(dispatcher.wasDispatched("da"), "blocking:false means run anyway and see the failure");
		assertFalse(dispatcher.wasDispatched("db"), "a blocking consumer of a failed dependency still skips");
	}

	// ------------------------------------------------------- per element and gather ---

	/**
	 * src → A(splitter, 3 texts) → tr(text-router, per element); tr.a → w, tr.a → coll.
	 *
	 * <p>
	 * {@code w} takes one text so it runs per element; {@code coll} gathers the sequence and runs
	 * once. Both are wired to the same selective port, which is what makes this the interesting
	 * case: the two must disagree about how many times to run but agree about which elements exist.
	 * </p>
	 */
	private PipelineGraph perElement() {
		return parse("per-element",
			new JsonArray()
				.add(node("src", "test-source").put("source", true))
				.add(node("A", "splitter"))
				.add(node("tr", "text-router"))
				.add(node("w", "worker"))
				.add(node("coll", "collector")),
			new JsonArray()
				.add(edge("src", "media", "A", "media"))
				.add(edge("A", "texts", "tr", "text"))
				.add(edge("tr", "a", "w", "text"))
				.add(edge("tr", "a", "coll", "items")));
	}

	@Test
	void testRoutingIsDecidedPerElement() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(perElement(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);

		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("A"),
			outputs("texts", sequence(item, TEXT_PLAIN, "one", "two", "three"))));

		// Three per-element router executions. Elements 0 and 2 go down 'a'; element 1 goes down 'b'.
		List<NodeTask> routerTasks = dispatcher.dispatched().stream().filter(t -> t.getNodeId().equals("tr")).toList();
		assertEquals(3, routerTasks.size(), "the router runs once per element");

		for (NodeTask task : routerTasks) {
			int seq = task.getElementSeq();
			String port = seq == 1 ? "b" : "a";
			engine.onNodeTaskResult(item, NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), seq, 5,
				outputs(port, element(item, seq, 3, TEXT_PLAIN, "v" + seq))));
		}

		List<Integer> workerSeqs = dispatcher.dispatched().stream()
			.filter(t -> t.getNodeId().equals("w"))
			.map(NodeTask::getElementSeq)
			.toList();
		assertEquals(List.of(0, 2), workerSeqs,
			"only the elements routed to 'a' run downstream; element 1 went the other way");
	}

	@Test
	void testAGatherReceivesOnlyTheElementsThatWereRouted() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(perElement(), dispatcher, UUID.randomUUID());

		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("A"),
			outputs("texts", sequence(item, TEXT_PLAIN, "one", "two", "three"))));

		for (NodeTask task : dispatcher.dispatched().stream().filter(t -> t.getNodeId().equals("tr")).toList()) {
			int seq = task.getElementSeq();
			String port = seq == 1 ? "b" : "a";
			engine.onNodeTaskResult(item, NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), seq, 5,
				outputs(port, element(item, seq, 3, TEXT_PLAIN, "v" + seq))));
		}
		// Let the per-element workers settle so the gather's dependencies are complete.
		for (NodeTask task : dispatcher.dispatched().stream().filter(t -> t.getNodeId().equals("w")).toList()) {
			engine.onNodeTaskResult(item, NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(),
				task.getElementSeq(), 5, outputs("result", element(item, task.getElementSeq(), 3, TEXT_PLAIN, "done"))));
		}

		assertTrue(dispatcher.wasDispatched("coll"), "the gather runs once the branch has settled");
		PortPayload items = dispatcher.taskFor("coll").getInputs().get("items");
		assertEquals(2, items.getElements().size(),
			"the gather sees the two elements that took this branch, not all three");
		assertEquals(List.of(0, 2), items.getElements().stream().map(e -> e.getOrigin().getSeq()).toList(),
			"and they keep their original sequence indices, so a later zip still aligns");
	}
}
