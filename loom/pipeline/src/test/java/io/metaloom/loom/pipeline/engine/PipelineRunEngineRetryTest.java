package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Retry, dead-letter and lost-task handling.
 *
 * <p>The property that matters most here is that a lost task always reaches one of
 * two ends - retried or dead-lettered. A reclaimed task that is neither leaves its
 * item stuck mid-graph and the run never completes, which is a worse failure than
 * the one being recovered from.</p>
 */
public class PipelineRunEngineRetryTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/**
	 * src -> hash, where hash carries the given options.
	 */
	private PipelineGraph graphWithHashOptions(JsonObject hashOptions) {
		JsonObject hash = new JsonObject().put("id", "hash").put("type", "sha512");
		if (hashOptions != null) {
			hash.put("options", hashOptions);
		}
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(hash))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash")));
		return parser.parse("retry", definition, true, false, 0);
	}

	private PipelineGraph retryableGraph() {
		return graphWithHashOptions(new JsonObject().put("retryFailed", true));
	}

	private PipelineGraph nonRetryableGraph() {
		return graphWithHashOptions(null);
	}

	@Test
	void testANodeWithoutRetryIsSettledOnFirstFailure() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(nonRetryableGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "boom"));

		assertEquals(1, dispatcher.dispatched().size(), "Default behaviour is unchanged: one attempt");
		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("hash").getState());
		assertTrue(engine.isComplete());
	}

	@Test
	void testRetryFailedActuallyRetries() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		NodeTask first = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(first.getTaskUuid(), "hash", 1, "transient"));

		// Before this, `retryFailed` was advertised by ten descriptors and read by
		// nothing - a node that declared itself retryable was retried zero times.
		assertEquals(2, dispatcher.dispatched().size(), "The node must be dispatched a second time");
		assertFalse(engine.getItem(itemId).getResults().containsKey("hash"),
			"A retried node is not settled yet");
	}

	@Test
	void testRetriesStopAtTheAttemptCeiling() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		// Fail every attempt. retryFailed:true means 2 attempts total.
		for (int i = 0; i < 5; i++) {
			if (engine.getItem(itemId).getResults().containsKey("hash")) {
				break;
			}
			NodeTask task = dispatcher.dispatched().get(dispatcher.dispatched().size() - 1);
			engine.onNodeTaskResult(itemId, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "still broken"));
		}

		assertEquals(2, dispatcher.dispatched().size(), "A poison item must not retry forever");
		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("hash").getState());
		assertTrue(engine.isComplete());
	}

	@Test
	void testExplicitMaxAttemptsWinsOverTheBoolean() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineGraph graph = graphWithHashOptions(new JsonObject().put("retryFailed", true).put("maxAttempts", 4));
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		for (int i = 0; i < 10; i++) {
			if (engine.getItem(itemId).getResults().containsKey("hash")) {
				break;
			}
			NodeTask task = dispatcher.dispatched().get(dispatcher.dispatched().size() - 1);
			engine.onNodeTaskResult(itemId, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "nope"));
		}

		assertEquals(4, dispatcher.dispatched().size());
	}

	@Test
	void testALostTaskIsRetried() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// The worker died holding the task; the lease lapsed and the reaper reclaimed it.
		engine.onNodeTaskLost(itemId, "hash", "lease expired");

		assertEquals(2, dispatcher.dispatched().size(), "A reclaimed task must be handed to someone else");
	}

	@Test
	void testALostTaskWithNoRetriesLeftIsDeadLettered() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(nonRetryableGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		engine.onNodeTaskLost(itemId, "hash", "lease expired");

		// The crucial property: a lost task is never simply forgotten. If it were, the
		// item would sit unsettled and the run would never complete.
		NodeTaskResult result = engine.getItem(itemId).getResults().get("hash");
		assertEquals(NodeState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("Dead-lettered"),
			"The dead-letter record must say why, got: " + result.getMessage());
		assertTrue(result.getMessage().contains("lease expired"), "The original reason must survive");
		assertTrue(engine.isComplete(), "The run must be able to finish");
	}

	@Test
	void testReclaimingAnAlreadySettledTaskIsIgnored() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(nonRetryableGraph(), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, Map.of()));

		// The result won the race against the reaper. Overwriting a success with a
		// dead-letter here would corrupt a finished item.
		engine.onNodeTaskLost(itemId, "hash", "lease expired");

		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("hash").getState());
		assertEquals(1, dispatcher.dispatched().size(), "Nothing should be re-dispatched");
	}

	@Test
	void testReclaimingAnUnknownItemIsIgnored() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(nonRetryableGraph(), dispatcher, UUID.randomUUID());
		engine.start();

		// A stale reaper entry for a run that has already been cleaned up must not
		// throw - it arrives on a timer thread with nobody to catch it.
		engine.onNodeTaskLost("no-such-item", "hash", "lease expired");
	}

	@Test
	void testRetryIsDeferredThroughTheScheduler() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, UUID.randomUUID());

		List<Long> delays = new ArrayList<>();
		List<Runnable> deferred = new ArrayList<>();
		engine.setRetryScheduler((delayMs, retry) -> {
			delays.add(delayMs);
			deferred.add(retry);
		});

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "transient"));

		// The engine must not re-dispatch on its own - retrying a failing node in a
		// tight loop turns one broken worker into a stampede.
		assertEquals(1, dispatcher.dispatched().size(), "Nothing is re-dispatched until the scheduler fires");
		assertEquals(1, delays.size());
		assertTrue(delays.get(0) > 0, "A retry must actually back off, got: " + delays.get(0));

		deferred.get(0).run();
		assertEquals(2, dispatcher.dispatched().size());
	}

	@Test
	void testBackoffGrowsAndIsCapped() {
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), new FakeNodeDispatcher(), UUID.randomUUID());
		engine.setRetryBaseDelayMs(1000);

		assertEquals(0, engine.backoffFor(0));
		assertEquals(1000, engine.backoffFor(1));
		assertEquals(2000, engine.backoffFor(2));
		assertEquals(4000, engine.backoffFor(3));
		// Without the cap, a high attempt count would park a node for hours.
		assertEquals(PipelineRunEngine.MAX_RETRY_DELAY_MS, engine.backoffFor(30));
	}

	@Test
	void testADeferredRetryOnAFinishedRunIsHarmless() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, UUID.randomUUID());

		List<Runnable> deferred = new ArrayList<>();
		engine.setRetryScheduler((delayMs, retry) -> deferred.add(retry));

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		NodeTask task = dispatcher.taskFor("hash");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(task.getTaskUuid(), "hash", 1, "transient"));

		// Second attempt succeeds, closing the run.
		deferred.get(0).run();
		NodeTask retried = dispatcher.dispatched().get(1);
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(retried.getTaskUuid(), "hash", 5, Map.of()));
		assertTrue(engine.isComplete());

		// A timer that fires after the run closed must not resurrect anything.
		int dispatchedBefore = dispatcher.dispatched().size();
		deferred.forEach(Runnable::run);
		assertEquals(dispatchedBefore, dispatcher.dispatched().size());
	}

}
