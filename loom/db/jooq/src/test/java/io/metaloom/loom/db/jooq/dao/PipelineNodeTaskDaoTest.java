package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static io.metaloom.loom.db.jooq.dao.PipelineFixtures.rootCauseMessage;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

/**
 * ⚠️ <b>This class is at the connection ceiling.</b> {@code JooqTestContext.afterEach} is commented
 * out, so every test leaves its c3p0 pool and leased database behind; at 21 test methods the next
 * one fails with {@code FATAL: sorry, too many clients already} — which surfaces 30 seconds later
 * as an unrelated-looking "could not acquire a connection" on whichever test ran last. Until that
 * teardown is restored, fold new coverage into an existing method rather than adding one.
 */
public class PipelineNodeTaskDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineNodeTaskDao, PipelineNodeTask> {

	@Override
	public PipelineNodeTaskDao getDao() {
		return pipelineNodeTaskDao();
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}

	@Override
	public PipelineNodeTask createElement(User user, int i) {
		PipelineRunItem item = storeItem(user, i);
		PipelineNodeTask task = pipelineNodeTaskDao().createNodeTask(user.getUuid(), item.getUuid(), item.getRunUuid(),
			"sha512", "hash-sha512");
		task.setMaxAttempts(3);
		return task;
	}

	@Override
	public void assertCreate(PipelineNodeTask created) {
		assertNotNull(created.getItemUuid());
		assertNotNull(created.getRunUuid());
		assertEquals("sha512", created.getNodeId());
		assertEquals("hash-sha512", created.getNodeKind());
		assertEquals(NodeTaskState.PENDING, created.getState(), "A fresh task has not been dispatched yet");
		assertEquals(0, created.getAttempt());
		assertEquals(3, created.getMaxAttempts());
		assertNull(created.getLeasedBy());
		assertNull(created.getLeaseExpiresAt());
	}

	@Override
	public void updateElement(PipelineNodeTask element) {
		element.setState(NodeTaskState.COMPLETED);
		element.setAttempt(1);
		element.setDurationMs(1234L);
		element.setOutputs(new JsonObject().put("sha512", "deadbeef"));
		element.setPreviews(new JsonObject().put("thumbnail",
			new JsonObject().put("mimeType", "image/jpeg").put("width", 512).put("height", 288).put("data", "AQID")));
	}

	@Override
	public void assertUpdate(PipelineNodeTask updated) {
		assertEquals(NodeTaskState.COMPLETED, updated.getState());
		assertEquals(1, updated.getAttempt());
		assertEquals(1234L, updated.getDurationMs());
		assertNotNull(updated.getOutputs(), "Outputs must survive the round trip - downstream nodes read them");
		assertEquals("deadbeef", updated.getOutputs().getString("sha512"));
		// Previews ride in a second JSONB column and need their own converter entry in the jOOQ
		// config; without it they round-trip as a string and every thumbnail silently breaks.
		assertNotNull(updated.getPreviews(), "Previews must survive the round trip");
		assertEquals("image/jpeg", updated.getPreviews().getJsonObject("thumbnail").getString("mimeType"));
		assertEquals("AQID", updated.getPreviews().getJsonObject("thumbnail").getString("data"));
	}

	@Test
	public void testANodeRunsAtMostOncePerItem() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "sha512", "hash-sha512", NodeTaskState.COMPLETED);

		// This is the idempotency key doing its job. Once retries exist, duplicate
		// delivery is inevitable, and without this a redelivered task would produce a
		// second execution record and a second set of outputs.
		PipelineNodeTask duplicate = pipelineNodeTaskDao().createNodeTask(user.getUuid(), item.getUuid(),
			item.getRunUuid(), "sha512", "hash-sha512");
		Exception thrown = assertThrows(Exception.class, () -> pipelineNodeTaskDao().store(duplicate),
			"The same node must not be recorded twice against one item");

		// Naming the constraint keeps this from passing on any random failure.
		assertTrue(rootCauseMessage(thrown).contains("pipeline_node_task_unique_node"),
			"Expected the idempotency constraint to reject it, got: " + rootCauseMessage(thrown));
	}

	@Test
	public void testDifferentNodesOnTheSameItemCoexist() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "sha512", "hash-sha512", NodeTaskState.COMPLETED);
		storeTask(user, item, "md5", "hash-md5", NodeTaskState.PENDING);

		assertEquals(2, pipelineNodeTaskDao().loadByItem(item.getUuid()).size());
	}

	@Test
	public void testLoadByItemAndNode() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		storeTask(user, item, "sha512", "hash-sha512", NodeTaskState.COMPLETED);

		PipelineNodeTask found = pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "sha512", 0);
		assertNotNull(found);
		assertEquals(NodeTaskState.COMPLETED, found.getState());

		assertNull(pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "not-a-node", 0),
			"An unknown node id must read as absent, not blow up");
	}

	@Test
	public void testExpiredLeasesAreFoundAndLiveOnesAreNot() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		Instant now = Instant.now();

		PipelineNodeTask expired = storeTask(user, item, "expired", "hash-sha512", NodeTaskState.RUNNING);
		expired.setLeasedBy("worker-a");
		expired.setLeaseExpiresAt(now.minus(Duration.ofMinutes(5)));
		pipelineNodeTaskDao().update(expired);

		PipelineNodeTask live = storeTask(user, item, "live", "hash-md5", NodeTaskState.RUNNING);
		live.setLeasedBy("worker-b");
		live.setLeaseExpiresAt(now.plus(Duration.ofMinutes(5)));
		pipelineNodeTaskDao().update(live);

		// A task whose worker died is only recoverable if this query finds it. The
		// lease columns are TIMESTAMP WITHOUT TIME ZONE, so this also pins down that
		// Instant→column conversion agrees between write and read; a zone mismatch
		// here would reap live leases or never reap dead ones.
		List<PipelineNodeTask> reclaimable = pipelineNodeTaskDao().loadExpiredLeases(now, 100);

		assertEquals(1, reclaimable.size(), "Exactly the lapsed lease is reclaimable");
		assertEquals("expired", reclaimable.get(0).getNodeId());
	}

	@Test
	public void testPendingTasksAreNotTreatedAsExpiredLeases() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		// PENDING with no lease at all. It is already dispatchable; reclaiming it
		// would be a no-op at best and a double dispatch at worst.
		storeTask(user, item, "pending", "hash-sha512", NodeTaskState.PENDING);

		assertTrue(pipelineNodeTaskDao().loadExpiredLeases(Instant.now(), 100).isEmpty());
	}

	@Test
	public void testExpiredLeaseSweepRespectsItsLimit() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		Instant now = Instant.now();

		for (int i = 0; i < 5; i++) {
			PipelineNodeTask task = storeTask(user, item, "node-" + i, "hash-sha512", NodeTaskState.RUNNING);
			task.setLeaseExpiresAt(now.minus(Duration.ofMinutes(i + 1L)));
			pipelineNodeTaskDao().update(task);
		}

		assertEquals(2, pipelineNodeTaskDao().loadExpiredLeases(now, 2).size(),
			"A sweep must be bounded - a reaper that loads a million rows is a new outage");
	}

	@Test
	public void testCountLeasedByTracksOnlyRunningWork() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		PipelineNodeTask running = storeTask(user, item, "running", "hash-sha512", NodeTaskState.RUNNING);
		running.setLeasedBy("worker-a");
		pipelineNodeTaskDao().update(running);

		// Still stamped with the worker that ran it, but finished. Counting this
		// against the worker's in-flight cap would strangle it over time.
		PipelineNodeTask done = storeTask(user, item, "done", "hash-md5", NodeTaskState.COMPLETED);
		done.setLeasedBy("worker-a");
		pipelineNodeTaskDao().update(done);

		assertEquals(1, pipelineNodeTaskDao().countLeasedBy("worker-a"));
		assertEquals(0, pipelineNodeTaskDao().countLeasedBy("worker-b"));
	}

	@Test
	public void testLoadLeasedByFindsUnexpiredWorkOfOneWorker() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		Instant now = Instant.now();

		// Lease still valid: loadExpiredLeases cannot see it, which is exactly why this query
		// exists. A worker evicted for silence must give its work back now, not a lease later.
		PipelineNodeTask held = storeTask(user, item, "held", "hash-sha512", NodeTaskState.RUNNING);
		held.setLeasedBy("evicted-worker");
		held.setLeaseExpiresAt(now.plus(Duration.ofMinutes(5)));
		pipelineNodeTaskDao().update(held);

		PipelineNodeTask other = storeTask(user, item, "other", "hash-md5", NodeTaskState.RUNNING);
		other.setLeasedBy("surviving-worker");
		other.setLeaseExpiresAt(now.plus(Duration.ofMinutes(5)));
		pipelineNodeTaskDao().update(other);

		// Finished, but still stamped with the worker. Handing this back would re-run work
		// that already has a result.
		PipelineNodeTask done = storeTask(user, item, "done", "hash-md5", NodeTaskState.COMPLETED);
		done.setLeasedBy("evicted-worker");
		pipelineNodeTaskDao().update(done);

		List<PipelineNodeTask> reclaimable = pipelineNodeTaskDao().loadLeasedBy("evicted-worker", 100);

		assertEquals(1, reclaimable.size(), "Only the departed worker's running work is reclaimable");
		assertEquals("held", reclaimable.get(0).getNodeId());
		assertTrue(pipelineNodeTaskDao().loadExpiredLeases(now, 1000).stream()
			.noneMatch(task -> held.getUuid().equals(task.getUuid())),
			"and it was not reachable through the lapsed-lease query");

		// Bounded, like the lapsed-lease sweep: one departure must not be able to load an
		// unbounded number of rows. Asserted here rather than in a test of its own because this
		// class already sits at the connection ceiling described in the class comment.
		for (int i = 0; i < 4; i++) {
			PipelineNodeTask task = storeTask(user, item, "bounded-" + i, "hash-sha512", NodeTaskState.RUNNING);
			task.setLeasedBy("evicted-worker");
			task.setLeaseExpiresAt(now.plus(Duration.ofMinutes(i + 1L)));
			pipelineNodeTaskDao().update(task);
		}
		assertEquals(2, pipelineNodeTaskDao().loadLeasedBy("evicted-worker", 2).size());
	}

	@Test
	public void testCountByRunAndState() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "a", "hash-sha512", NodeTaskState.COMPLETED);
		storeTask(user, item, "b", "hash-md5", NodeTaskState.COMPLETED);
		storeTask(user, item, "c", "hash-sha512", NodeTaskState.FAILED);

		UUID runUuid = item.getRunUuid();
		assertEquals(2, pipelineNodeTaskDao().countByRunAndState(runUuid, NodeTaskState.COMPLETED));
		assertEquals(1, pipelineNodeTaskDao().countByRunAndState(runUuid, NodeTaskState.FAILED));
	}

	@Test
	public void testDeletingAnItemCascadesToItsTasks() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		storeTask(user, item, "sha512", "hash-sha512", NodeTaskState.COMPLETED);

		pipelineRunItemDao().delete(item.getUuid());

		assertEquals(0, pipelineNodeTaskDao().loadByItem(item.getUuid()).size());
	}

	private PipelineRunItem storeItem(User user, int i) {
		PipelineRun run = PipelineFixtures.createRun(this, user, i);
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), i, "/media/file-" + i + ".mp4");
		pipelineRunItemDao().store(item);
		return item;
	}

	private PipelineNodeTask storeTask(User user, PipelineRunItem item, String nodeId, String nodeKind, NodeTaskState state) {
		return storeTask(user, item, nodeId, nodeKind, state, 0);
	}

	private PipelineNodeTask storeTask(User user, PipelineRunItem item, String nodeId, String nodeKind, NodeTaskState state,
		int elementSeq) {
		return storeTask(user, item, nodeId, nodeKind, state, elementSeq, 0);
	}

	private PipelineNodeTask storeTask(User user, PipelineRunItem item, String nodeId, String nodeKind, NodeTaskState state,
		int elementSeq, int generation) {
		PipelineNodeTask task = pipelineNodeTaskDao().createNodeTask(user.getUuid(), item.getUuid(), item.getRunUuid(),
			nodeId, nodeKind);
		task.setState(state);
		// Set before the insert: element_seq and generation are part of the unique key, so a row
		// cannot be written as element 0 generation 0 and moved afterwards.
		task.setElementSeq(elementSeq);
		task.setGeneration(generation);
		pipelineNodeTaskDao().store(task);
		return task;
	}


	@Test
	public void testEachElementOfAFannedOutNodeGetsItsOwnRow() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "describe", "facedescription", NodeTaskState.COMPLETED, 0);
		storeTask(user, item, "describe", "facedescription", NodeTaskState.FAILED, 1);

		PipelineNodeTask element0 = pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "describe", 0);
		PipelineNodeTask element1 = pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "describe", 1);
		assertNotNull(element0, "Element 0 must keep its own row");
		assertNotNull(element1, "Element 1 must keep its own row");
		assertEquals(NodeTaskState.COMPLETED, element0.getState());
		assertEquals(NodeTaskState.FAILED, element1.getState(),
			"One element failing must not be readable as the whole node failing, or succeeding");
	}

	@Test
	public void testEachReExecutionKeepsItsOwnRow() {
		// Comparing "before" with "after" is the entire reason to re-execute a node. An UPDATE would
		// destroy the comparison at the moment it became interesting, so the generation is part of
		// the unique key and both attempts survive.
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.COMPLETED, 0, 0);
		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.COMPLETED, 0, 1);

		assertEquals(2, pipelineNodeTaskDao().loadByItem(item.getUuid()).size(),
			"Both attempts at the same execution must survive");
		assertEquals(0, pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "facedetect", 0, 0).getGeneration());
		assertEquals(1, pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "facedetect", 0, 1).getGeneration());
	}

	@Test
	public void testLoadByItemAndNodeReturnsTheLatestAttempt() {
		// Every caller of the three-argument lookup - settling a task, adopting an earlier run's
		// result - means "the current attempt". Without the ordering it would get whichever row the
		// planner happened to hand back, which is a bug that only appears once a node is re-executed.
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.FAILED, 0, 0);
		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.COMPLETED, 0, 1);

		PipelineNodeTask latest = pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "facedetect", 0);
		assertEquals(1, latest.getGeneration());
		assertEquals(NodeTaskState.COMPLETED, latest.getState());
	}

	@Test
	public void testDeletingAnItemCascadesToEveryGeneration() {
		// Re-executing a node several times must not leave rows behind when the run is pruned:
		// extra generations are run-scoped diagnostics with exactly the retention of the row they
		// sit beside.
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.COMPLETED, 0, 0);
		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.COMPLETED, 0, 1);
		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.COMPLETED, 0, 2);

		pipelineRunItemDao().delete(item.getUuid());

		assertEquals(0, pipelineNodeTaskDao().loadByItem(item.getUuid()).size());
	}

	@Test
	public void testTheSameGenerationCannotBeRecordedTwice() {
		// The idempotency key grew a column but did not stop being one. A second row for the same
		// attempt would make "which result did this attempt produce?" ambiguous.
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		storeTask(user, item, "facedetect", "facedetect", NodeTaskState.COMPLETED, 0, 1);

		Exception e = assertThrows(Exception.class,
			() -> storeTask(user, item, "facedetect", "facedetect", NodeTaskState.FAILED, 0, 1));
		assertTrue(rootCauseMessage(e).contains("pipeline_node_task_unique_node"),
			"The unique key must still reject a duplicate attempt: " + rootCauseMessage(e));
	}

}
