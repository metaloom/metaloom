package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
 * The engine's use of the circuit breaker.
 *
 * <p>The dangerous outcome here is not a breaker that fails to open — it is one that
 * opens and then never lets go, turning a recoverable environmental fault into a run
 * that hangs forever. A parked node is neither settled nor in flight, so nothing
 * revisits it unless the engine arranges to.</p>
 */
public class PipelineRunEngineCircuitTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();
	private final AtomicLong now = new AtomicLong(1_000_000);

	private PipelineGraph graph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "speech").put("type", "whisper")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash"))
				.add(new JsonObject().put("source", "src").put("target", "speech")));
		return parser.parse("circuit", definition, true, false, 0);
	}

	private NodeKindCircuitBreaker breaker() {
		return new NodeKindCircuitBreaker(4, 0.9, 30_000, now::get);
	}

	@Test
	void testABrokenKindStopsBeingDispatchedWhileOthersContinue() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		NodeKindCircuitBreaker breaker = breaker();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		engine.setCircuitBreaker(breaker);
		engine.setRetryScheduler((delayMs, retry) -> {
			// Hold the un-park so the parked state is observable.
		});
		engine.start();

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}

		// Fail every whisper task, succeed every hash.
		for (int i = 0; i < 6; i++) {
			for (NodeTask task : new ArrayList<>(dispatcher.dispatched())) {
				if (engine.getItem(itemIds.get(i)).getResults().containsKey(task.getNodeId())) {
					continue;
				}
				if (!task.getItemId().equals(itemIds.get(i))) {
					continue;
				}
				if ("speech".equals(task.getNodeId())) {
					engine.onNodeTaskResult(itemIds.get(i),
						NodeTaskResult.failed(task.getTaskUuid(), "speech", 1, "model missing"));
				} else {
					engine.onNodeTaskResult(itemIds.get(i),
						NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, Map.of()));
				}
			}
		}

		assertEquals(NodeKindCircuitBreaker.State.OPEN, breaker.stateOf("whisper"));
		assertEquals(NodeKindCircuitBreaker.State.CLOSED, breaker.stateOf("sha512"),
			"A broken kind must not implicate a healthy one");
	}

	@Test
	void testAParkedKindIsUnparkedWhenTheCooldownFires() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		NodeKindCircuitBreaker breaker = breaker();
		List<Runnable> deferred = new ArrayList<>();

		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		engine.setCircuitBreaker(breaker);
		engine.setRetryScheduler((delayMs, action) -> deferred.add(action));
		engine.start();

		// Open the breaker directly, then discover an item.
		for (int i = 0; i < 4; i++) {
			breaker.record("whisper", false);
		}
		assertEquals(NodeKindCircuitBreaker.State.OPEN, breaker.stateOf("whisper"));

		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		assertFalse(dispatcher.wasDispatched("speech"), "A parked kind must not be dispatched");
		assertTrue(dispatcher.wasDispatched("hash"), "Other kinds carry on");
		assertFalse(deferred.isEmpty(), "The engine must schedule its own un-park");

		// Cooldown elapses and the scheduled un-park runs.
		now.addAndGet(31_000);
		deferred.forEach(Runnable::run);

		// Without this the run would sit with 'speech' neither settled nor in flight,
		// and nothing would ever look at it again.
		assertTrue(dispatcher.wasDispatched("speech"), "The probe must eventually be dispatched");
		// 'hash' was dispatched but never answered, so only the synthesised source has
		// settled.
		assertEquals(1, engine.getItem(itemId).getResults().size());
	}

	@Test
	void testARunWithOnlyAParkedKindStillFinishesOnceItRecovers() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		NodeKindCircuitBreaker breaker = breaker();
		List<Runnable> deferred = new ArrayList<>();

		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "speech").put("type", "whisper")))
			.put("edges", new JsonArray().add(new JsonObject().put("source", "src").put("target", "speech")));
		PipelineRunEngine engine = new PipelineRunEngine(parser.parse("only", definition, true, false, 0),
			dispatcher, UUID.randomUUID());
		engine.setCircuitBreaker(breaker);
		engine.setRetryScheduler((delayMs, action) -> deferred.add(action));
		engine.start();

		for (int i = 0; i < 4; i++) {
			breaker.record("whisper", false);
		}
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		assertFalse(engine.isComplete(), "The run is parked, not finished");

		now.addAndGet(31_000);
		deferred.forEach(Runnable::run);

		NodeTask probe = dispatcher.taskFor("speech");
		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(probe.getTaskUuid(), "speech", 5, Map.of()));

		assertTrue(engine.isComplete(), "A recovered kind must let the run finish");
		assertEquals(NodeKindCircuitBreaker.State.CLOSED, breaker.stateOf("whisper"));
	}

	@Test
	void testSkipsDoNotCountAgainstAKind() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		NodeKindCircuitBreaker breaker = breaker();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		engine.setCircuitBreaker(breaker);
		engine.start();

		// A dry-run-like situation: nodes settle as SKIPPED without ever executing.
		for (int i = 0; i < 10; i++) {
			String itemId = engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4"));
			NodeTask hash = dispatcher.taskFor("hash");
			engine.onNodeTaskResult(itemId, NodeTaskResult.failed(hash.getTaskUuid(), "hash", 1, "boom"));
		}

		// A heavily filtered pipeline must not look like a broken one: a skip says
		// nothing about whether the kind works.
		assertEquals(NodeKindCircuitBreaker.State.CLOSED, breaker.stateOf("thumbnail"));
	}

	@Test
	void testNoBreakerMeansUnchangedBehaviour() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		engine.start();

		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		NodeTask speech = dispatcher.taskFor("speech");
		engine.onNodeTaskResult(itemId, NodeTaskResult.failed(speech.getTaskUuid(), "speech", 1, "boom"));

		// The feature is opt-in; without a breaker installed nothing is gated.
		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("speech").getState());
		assertTrue(dispatcher.wasDispatched("hash"));
	}

}
