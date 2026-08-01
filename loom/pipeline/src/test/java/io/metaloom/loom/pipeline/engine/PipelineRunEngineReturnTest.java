package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Work handed back by a draining worker.
 *
 * <p>A return is not a failure and must not be accounted as one. Nodes are not
 * retryable by default, so charging a return against the attempt budget would make a
 * rolling restart dead-letter every item that happened to be in flight - turning a
 * routine deployment into data loss. The other half of the property is that a return
 * is <em>immediate</em>: if it merely queued behind the lease it would save nothing
 * over saying nothing at all.</p>
 */
public class PipelineRunEngineReturnTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/** src -> hash, with hash not retryable - the default, and the case that matters. */
	private PipelineGraph graph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media")
					.put("target", "hash").put("targetPort", "media")));
		return parser.parse("drain", definition, true, false, 0);
	}

	private String startWithOneItem(PipelineRunEngine engine) {
		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		return itemId;
	}

	@Test
	void testAReturnedTaskIsDispatchedAgainImmediately() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		String itemId = startWithOneItem(engine);
		assertEquals(1, dispatcher.dispatched().size(), "Precondition: hash was placed once");

		engine.onNodeTaskReturned(itemId, "hash", 0, "worker is shutting down");

		assertEquals(2, dispatcher.dispatched().size(),
			"A returned task must be placed again straight away, not left for the lease reaper");
		assertFalse(engine.isComplete(), "The item is back in flight, not settled");
	}

	@Test
	void testAReturnDoesNotSpendTheAttemptBudget() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		String itemId = startWithOneItem(engine);

		// hash is not retryable: one attempt is all it gets. If the return spent it, this
		// second placement would be the one and only, and the node would dead-letter the
		// moment anything else went wrong.
		engine.onNodeTaskReturned(itemId, "hash", 0, "worker is shutting down");
		engine.onNodeTaskReturned(itemId, "hash", 0, "second worker is shutting down too");

		assertEquals(3, dispatcher.dispatched().size(),
			"Neither return may be charged as an attempt against a non-retryable node");
		assertNull(engine.getItem(itemId).getResults().get("hash"),
			"A returned node must not be settled - nothing ran");
	}

	@Test
	void testTheResultOfAReturnedTaskStillSettlesTheNode() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		String itemId = startWithOneItem(engine);

		NodeTask first = dispatcher.taskFor("hash");
		engine.onNodeTaskReturned(itemId, "hash", 0, "worker is shutting down");
		// The draining worker finished after all, and its answer arrives late. It must be
		// taken: returning is a hint that the work may need re-placing, not a cancellation.
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(first.getTaskUuid(), "hash", 5, java.util.Map.of()));

		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("hash").getState());
		assertTrue(engine.isComplete());
	}

	@Test
	void testAReturnAfterTheResultIsIgnored() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		String itemId = startWithOneItem(engine);

		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, java.util.Map.of()));
		engine.onNodeTaskReturned(itemId, "hash", 0, "worker is shutting down");

		assertEquals(1, dispatcher.dispatched().size(),
			"A settled node must not be re-placed by a return that lost the race");
		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("hash").getState());
	}

	@Test
	void testAnEndlesslyReturnedTaskEventuallySettles() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		String itemId = startWithOneItem(engine);

		// A worker that hands everything back without ever running it costs nothing to
		// do so. Free refunds forever would circulate the item around the fleet with no
		// attempt ever accumulating, and the run would never end.
		for (int i = 0; i < 10; i++) {
			engine.onNodeTaskReturned(itemId, "hash", 0, "bouncing");
		}

		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("hash").getState(),
			"Past the refund cap a return is accounted as a loss and the node dead-letters");
		assertTrue(engine.isComplete(), "The run must reach an end rather than circulate");
	}

	@Test
	void testAReturnForAnUnknownItemIsIgnored() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		startWithOneItem(engine);

		// A worker draining as a run finishes will address items the engine has forgotten.
		engine.onNodeTaskReturned("no-such-item", "hash", 0, "worker is shutting down");

		assertEquals(1, dispatcher.dispatched().size(), "Nothing was placed for an item that does not exist");
	}
}
