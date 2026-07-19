package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Backpressure onto the source.
 *
 * <p>Capping dispatch bounds how much work is <em>outstanding</em>. It does nothing
 * about how much has been <em>discovered</em> — a fast filesystem scan will happily
 * enumerate 100 000 files while 256 tasks run, and every one of them costs item
 * state. Withholding the batch acknowledgement is what stops the scan itself, and
 * therefore the only thing that actually bounds memory.</p>
 */
public class PipelineRunEngineBackpressureTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	private PipelineGraph graph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new JsonArray().add(new JsonObject().put("source", "src").put("target", "hash")));
		return parser.parse("backpressure", definition, true, false, 0);
	}

	private PipelineRunEngine engine(FakeNodeDispatcher dispatcher, int cap) {
		PipelineRunEngine engine = new PipelineRunEngine(graph(), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(cap);
		engine.start();
		return engine;
	}

	@Test
	void testTheAckIsSentImmediatelyWhenThereIsRoom() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 10);

		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		AtomicInteger acks = new AtomicInteger();
		engine.whenCapacityAvailable(acks::incrementAndGet);

		assertEquals(1, acks.get(), "An unloaded run must not slow the scan down");
	}

	@Test
	void testTheAckIsHeldBackWhileAtCapacity() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 2);

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}
		assertTrue(engine.isAtCapacity());

		AtomicInteger acks = new AtomicInteger();
		engine.whenCapacityAvailable(acks::incrementAndGet);

		// Without this the scan races ahead and the run holds item state for media it
		// will not look at for hours.
		assertEquals(0, acks.get(), "A saturated run must not invite more work");
	}

	@Test
	void testTheAckIsReleasedWhenCapacityFrees() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 2);

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}

		AtomicInteger acks = new AtomicInteger();
		engine.whenCapacityAvailable(acks::incrementAndGet);
		assertEquals(0, acks.get());

		NodeTask task = dispatcher.dispatched().get(0);
		engine.onNodeTaskResult(itemIds.get(0), NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, Map.of()));

		assertEquals(1, acks.get(), "Finishing work must invite the next batch");
	}

	@Test
	void testAWaitingSourceIsReleasedWhenTheRunCompletes() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 1);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		AtomicInteger acks = new AtomicInteger();
		engine.whenCapacityAvailable(acks::incrementAndGet);
		assertEquals(0, acks.get());

		NodeTask task = dispatcher.dispatched().get(0);
		engine.onNodeTaskResult(a, NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, Map.of()));

		assertTrue(engine.isComplete());
		// A source left blocked on a finished run would sit on its ack latch until the
		// timeout and then report the whole scan as failed.
		assertEquals(1, acks.get(), "A completed run must not strand a waiting source");
	}

	@Test
	void testAnAckOnAnAlreadyFinishedRunRunsImmediately() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 1);

		String a = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		NodeTask task = dispatcher.dispatched().get(0);
		engine.onNodeTaskResult(a, NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, Map.of()));
		assertTrue(engine.isComplete());

		AtomicInteger acks = new AtomicInteger();
		engine.whenCapacityAvailable(acks::incrementAndGet);

		assertEquals(1, acks.get(), "A late ack must never be queued against a run nobody will drain");
	}

	@Test
	void testEveryWaiterIsReleasedNotJustTheFirst() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = engine(dispatcher, 2);

		List<String> itemIds = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			itemIds.add(engine.onItemDiscovered(MediaRef.of("/media/" + i + ".mp4")));
		}

		AtomicInteger acks = new AtomicInteger();
		engine.whenCapacityAvailable(acks::incrementAndGet);
		engine.whenCapacityAvailable(acks::incrementAndGet);

		NodeTask task = dispatcher.dispatched().get(0);
		engine.onNodeTaskResult(itemIds.get(0), NodeTaskResult.completed(task.getTaskUuid(), "hash", 5, Map.of()));

		assertEquals(2, acks.get());
	}

}
