package io.metaloom.loom.pipeline.engine;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_SHA512;
import static io.metaloom.loom.pipeline.engine.Payloads.outputs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Re-executing a node held at a breakpoint, with different settings.
 *
 * <p>
 * Stopping at a node answers "what did it produce?". This answers the question that immediately
 * follows — "and what would it produce if I changed this?" — without re-running the pipeline from
 * the top and without discarding the attempt being compared against.
 * </p>
 *
 * <p>
 * Two properties carry most of the risk and are asserted throughout. First, <strong>the pipeline
 * definition is never written</strong>: an override lives in the engine and
 * {@code PipelineGraphNode.getOptions()} still reports what the pipeline says. Second, <strong>the
 * execution genuinely runs again</strong> rather than being marked done a second time — so the
 * assertions look at what reached the dispatcher, not merely at engine bookkeeping.
 * </p>
 */
public class PipelineRunEngineReExecuteTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	private static JsonObject node(String id, String kind) {
		return new JsonObject().put("id", id).put("type", kind);
	}

	private static JsonObject edge(String from, String sourcePort, String to, String targetPort) {
		return new JsonObject().put("source", from).put("sourcePort", sourcePort)
			.put("target", to).put("targetPort", targetPort);
	}

	/** src -> hash -> thumb, with hash carrying two settings from the definition. */
	private PipelineGraph linearGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("src", "filesystem-source").put("source", true))
				.add(node("hash", "sha512").put("options", new JsonObject()
					.put("algorithm", "sha512")
					.put("chunkSize", 4096)))
				.add(node("thumb", "thumbnail")))
			.put("edges", new JsonArray()
				.add(edge("src", "media", "hash", "media"))
				.add(edge("hash", "hash", "thumb", "media")));
		return parser.parse("linear", definition, true, false, 0);
	}

	private static MediaRef media(String path) {
		return MediaRef.of(path);
	}

	private static Map<String, PortPayload> hash(String value) {
		return outputs("hash", HASH_SHA512, value);
	}

	private static NodeTaskResult ok(NodeTask task, Map<String, PortPayload> outputs) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5, outputs);
	}

	/**
	 * Start a run, hold at {@code hash}, and settle its first execution.
	 *
	 * @return the item id
	 */
	private String runUntilHeld(PipelineRunEngine engine, FakeNodeDispatcher dispatcher) {
		engine.setBreakpoints(List.of("hash"));
		engine.start();
		String item = engine.onItemDiscovered(media("/media/a.mp4"));
		engine.onNodeTaskResult(item, ok(dispatcher.taskFor("hash"), hash("first")));
		return item;
	}

	@Test
	@DisplayName("re-executing a held node dispatches it again")
	void testReExecuteDispatchesAgain() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);
		assertEquals(1, dispatcher.tasksFor("hash").size());

		engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192));

		// The whole feature in one assertion: the node ran a second time. Anything that merely
		// cleared a flag, or re-recorded the old result, leaves this at 1.
		assertEquals(2, dispatcher.tasksFor("hash").size(), "The node must actually be dispatched again");
		assertFalse(dispatcher.wasDispatched("thumb"),
			"A re-execution must not let the run past the breakpoint it is still stopped at");
	}

	@Test
	@DisplayName("the re-executed task carries the new settings, merged over the pipeline's own")
	void testOverrideIsMergedOverDefinitionOptions() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineGraph graph = linearGraph();
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);

		engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192));

		NodeTask second = dispatcher.tasksFor("hash").get(1);
		assertEquals(8192, second.getOptions().get("chunkSize"), "The changed setting must reach the worker");
		assertEquals("sha512", second.getOptions().get("algorithm"),
			"Changing one setting must not drop the others - an override is a patch, not a replacement");

		// The property that makes experimenting on a live run safe.
		assertEquals(4096, graph.getNode("hash").getOptions().get("chunkSize"),
			"An override is run state and must never be written back into the pipeline definition");
	}

	@Test
	@DisplayName("the re-executed result is held at the same breakpoint again")
	void testResultIsHeldAgain() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);
		engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192));

		engine.onNodeTaskResult(item, ok(dispatcher.tasksFor("hash").get(1), hash("second")));

		assertEquals(1, engine.heldCount(), "The breakpoint is still armed, so the second attempt stops too");
		assertEquals("second", engine.getItem(item).getResults().get("hash").getOutputs()
			.get("hash").getElements().get(0).getValue(), "The new result must replace the one it was compared with");
		assertFalse(dispatcher.wasDispatched("thumb"));
	}

	@Test
	@DisplayName("releasing after a re-execution carries the new result downstream")
	void testReleaseAfterReExecuteUsesTheNewResult() {
		// The failure this guards against is subtle: a run that re-executes but then feeds its
		// dependents the *original* output would look entirely correct in the debug view and be
		// wrong everywhere else.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);
		engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192));
		engine.onNodeTaskResult(item, ok(dispatcher.tasksFor("hash").get(1), hash("second")));

		assertEquals(1, engine.releaseNode("hash"));

		assertTrue(dispatcher.wasDispatched("thumb"));
		assertEquals("second", dispatcher.taskFor("thumb").getInputs().get("media").getElements().get(0).getValue());
	}

	@Test
	@DisplayName("each re-execution is a new generation, counting from 1")
	void testGenerationIncrements() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);

		assertEquals(0, dispatcher.tasksFor("hash").get(0).getGeneration(), "The original run is generation 0");

		assertEquals(1, engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192)));
		engine.onNodeTaskResult(item, ok(dispatcher.tasksFor("hash").get(1), hash("second")));
		assertEquals(2, engine.reExecute(item, "hash", 0, Map.of("chunkSize", 16384)));

		List<NodeTask> tasks = dispatcher.tasksFor("hash");
		assertEquals(List.of(0, 1, 2), tasks.stream().map(NodeTask::getGeneration).toList(),
			"Attempts must be numbered so their task rows can sit side by side rather than overwrite");
	}

	@Test
	@DisplayName("an execution that is not held cannot be re-executed")
	void testOnlyHeldExecutionsMayBeReExecuted() {
		// The restriction is what makes the operation safe: a hold guarantees nothing downstream has
		// consumed the result being discarded. Re-executing a released node would need its dependents
		// invalidated transitively, which is a different and much larger feature.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);
		engine.releaseNode("hash");

		assertThrows(IllegalStateException.class, () -> engine.reExecute(item, "hash", 0, Map.of()));
		assertEquals(1, dispatcher.tasksFor("hash").size(), "A refused re-execution must not dispatch anything");
	}

	@Test
	@DisplayName("an unknown item or node is rejected rather than silently ignored")
	void testUnknownItemOrNodeIsRejected() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);

		assertThrows(IllegalArgumentException.class, () -> engine.reExecute("no-such-item", "hash", 0, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> engine.reExecute(item, "no-such-node", 0, Map.of()));
	}

	@Test
	@DisplayName("an override applies to the rest of the run, not just the item it was tried on")
	void testOverrideIsRunScoped() {
		// "Run-scoped" is the promise the API makes. A setting that only affected the one item it was
		// tried on would make the feature useless for deciding whether to keep the setting.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String first = runUntilHeld(engine, dispatcher);
		engine.reExecute(first, "hash", 0, Map.of("chunkSize", 8192));

		String second = engine.onItemDiscovered(media("/media/b.mp4"));

		assertEquals(8192, dispatcher.taskFor("hash", second).getOptions().get("chunkSize"));
	}

	@Test
	@DisplayName("null options re-run with whatever is already in effect; an empty map reverts")
	void testNullKeepsOverrideAndEmptyClearsIt() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);
		engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192));
		engine.onNodeTaskResult(item, ok(dispatcher.tasksFor("hash").get(1), hash("second")));

		// Re-running an unchanged node is legitimate - a flaky worker, or media that has since been
		// fixed - and must not silently wipe the setting being evaluated.
		engine.reExecute(item, "hash", 0, null);
		assertEquals(8192, dispatcher.tasksFor("hash").get(2).getOptions().get("chunkSize"));
		engine.onNodeTaskResult(item, ok(dispatcher.tasksFor("hash").get(2), hash("third")));

		engine.reExecute(item, "hash", 0, Map.of());
		assertEquals(4096, dispatcher.tasksFor("hash").get(3).getOptions().get("chunkSize"),
			"An empty map is how a caller says 'go back to what the pipeline says'");
		assertNull(engine.getOptionOverrides().get("hash"));
	}

	@Test
	@DisplayName("a re-execution reports the hold released and taken again")
	void testBreakpointFramesAreEmitted() {
		// The UI is showing a held node. Without the release frame it would stay held on screen while
		// the engine had already moved on, and the re-hold would look like nothing happened.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		List<String> frames = new ArrayList<>();
		engine.onBreakpoint((itemId, mediaPath, nodeId, elementSeq, held) -> frames.add(nodeId + ":" + held));

		String item = runUntilHeld(engine, dispatcher);
		assertEquals(List.of("hash:true"), frames);

		engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192));
		engine.onNodeTaskResult(item, ok(dispatcher.tasksFor("hash").get(1), hash("second")));

		assertEquals(List.of("hash:true", "hash:false", "hash:true"), frames);
	}

	@Test
	@DisplayName("a run holding a re-executed node still does not complete")
	void testRunDoesNotCompleteWhileReExecuting() {
		// checkComplete() refuses to close a run that is holding. A re-execution passes briefly
		// through a state where the node is neither held nor settled, and closing the run there
		// would clear the pause underneath the person debugging it.
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, UUID.randomUUID());
		String item = runUntilHeld(engine, dispatcher);
		engine.onSourceComplete(1);

		engine.reExecute(item, "hash", 0, Map.of("chunkSize", 8192));
		assertFalse(engine.isComplete(), "A run with a node still out at a worker cannot be complete");

		engine.onNodeTaskResult(item, ok(dispatcher.tasksFor("hash").get(1), hash("second")));
		assertFalse(engine.isComplete(), "A run holding at a breakpoint must stay open");
	}
}
