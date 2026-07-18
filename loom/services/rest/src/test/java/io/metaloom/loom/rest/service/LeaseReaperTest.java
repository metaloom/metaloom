package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.rest.service.impl.LeaseReaper;
import io.metaloom.loom.rest.service.impl.PipelineRunRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Reclaiming tasks from workers that never reported back.
 *
 * <p>The reaper is the only thing that turns a dead worker into progress, so the
 * behaviour under test is narrow but load-bearing: a lapsed task reaches its engine,
 * an orphaned one is retired instead of being swept forever, and neither path throws
 * on the timer thread where nobody is listening.</p>
 */
public class LeaseReaperTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/** Records dispatches so a reclaim can be observed as a re-dispatch. */
	private static class CountingDispatcher implements io.metaloom.loom.pipeline.engine.NodeDispatcher {

		final List<NodeTask> dispatched = new ArrayList<>();

		@Override
		public boolean dispatch(NodeTask task) {
			dispatched.add(task);
			return true;
		}
	}

	private PipelineGraph retryableGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")
					.put("options", new JsonObject().put("retryFailed", true))))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash")));
		return parser.parse("reaper", definition, true, false, 0);
	}

	@Test
	void testALapsedLeaseIsHandedBackToItsEngine() {
		CountingDispatcher dispatcher = new CountingDispatcher();
		UUID runUuid = UUID.randomUUID();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, runUuid);
		PipelineRunRegistry registry = new PipelineRunRegistry();
		registry.register(runUuid, engine);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		assertEquals(1, dispatcher.dispatched.size());

		FakeNodeTaskDao dao = new FakeNodeTaskDao();
		dao.expired.add(task(runUuid, UUID.fromString(itemId), "hash"));

		LeaseReaper reaper = new LeaseReaper(dao, registry);
		int reclaimed = reaper.sweep(Instant.now(), 100);

		assertEquals(1, reclaimed);
		// The point of the whole mechanism: work that was silently lost gets done.
		assertEquals(2, dispatcher.dispatched.size(), "The reclaimed task must be dispatched again");
	}

	@Test
	void testATaskWhoseRunIsGoneIsRetiredRatherThanSweptForever() {
		FakeNodeTaskDao dao = new FakeNodeTaskDao();
		PipelineNodeTask orphan = task(UUID.randomUUID(), UUID.randomUUID(), "hash");
		dao.expired.add(orphan);

		LeaseReaper reaper = new LeaseReaper(dao, new PipelineRunRegistry());
		int reclaimed = reaper.sweep(Instant.now(), 100);

		assertEquals(0, reclaimed, "There is no engine to hand it to");
		// Without this the row stays RUNNING with a past expiry and is re-read on every
		// sweep for the life of the process.
		assertEquals(1, dao.updated.size());
		assertEquals("DEAD_LETTER", dao.updated.get(0).getState());
		assertEquals(null, dao.updated.get(0).getLeaseExpiresAt(), "A retired task must not remain leased");
	}

	@Test
	void testAnEmptySweepDoesNothing() {
		FakeNodeTaskDao dao = new FakeNodeTaskDao();
		LeaseReaper reaper = new LeaseReaper(dao, new PipelineRunRegistry());

		assertEquals(0, reaper.sweep(Instant.now(), 100));
		assertTrue(dao.updated.isEmpty());
	}

	@Test
	void testReclaimingATaskWhoseResultAlreadyArrivedDoesNotDisturbIt() {
		CountingDispatcher dispatcher = new CountingDispatcher();
		UUID runUuid = UUID.randomUUID();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, runUuid);
		PipelineRunRegistry registry = new PipelineRunRegistry();
		registry.register(runUuid, engine);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		NodeTask dispatched = dispatcher.dispatched.get(0);
		engine.onNodeTaskResult(itemId, io.metaloom.loom.pipeline.model.NodeTaskResult
			.completed(dispatched.getTaskUuid(), "hash", 5, java.util.Map.of()));

		FakeNodeTaskDao dao = new FakeNodeTaskDao();
		dao.expired.add(task(runUuid, UUID.fromString(itemId), "hash"));

		// The result won the race against the reaper. A success must not be rewritten
		// into a dead-letter just because a sweep was already in flight.
		new LeaseReaper(dao, registry).sweep(Instant.now(), 100);

		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("hash").getState());
		assertEquals(1, dispatcher.dispatched.size());
	}

	@Test
	void testASweepThatThrowsOnOneTaskStillProcessesTheRest() {
		CountingDispatcher dispatcher = new CountingDispatcher();
		UUID runUuid = UUID.randomUUID();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, runUuid);
		PipelineRunRegistry registry = new PipelineRunRegistry();
		registry.register(runUuid, engine);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		FakeNodeTaskDao dao = new FakeNodeTaskDao();
		// A row with a null item uuid blows up on toString(). The sweep runs on a timer
		// thread, so one bad row must not abort the batch and stop all recovery.
		dao.expired.add(task(runUuid, null, "hash"));
		dao.expired.add(task(runUuid, UUID.fromString(itemId), "hash"));

		int reclaimed = new LeaseReaper(dao, registry).sweep(Instant.now(), 100);

		assertEquals(1, reclaimed, "The healthy task must still be reclaimed");
		assertEquals(2, dispatcher.dispatched.size());
	}

	private static PipelineNodeTask task(UUID runUuid, UUID itemUuid, String nodeId) {
		PipelineNodeTask task = new FakePipelineNodeTask();
		task.setUuid(UUID.randomUUID());
		task.setRunUuid(runUuid);
		task.setItemUuid(itemUuid);
		task.setNodeId(nodeId);
		task.setNodeKind("sha512");
		task.setState("RUNNING");
		task.setLeaseExpiresAt(Instant.now().minusSeconds(600));
		return task;
	}

}
