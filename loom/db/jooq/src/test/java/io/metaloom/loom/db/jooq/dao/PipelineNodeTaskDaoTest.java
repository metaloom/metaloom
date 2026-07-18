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

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

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
		assertEquals("PENDING", created.getState(), "A fresh task has not been dispatched yet");
		assertEquals(0, created.getAttempt());
		assertEquals(3, created.getMaxAttempts());
		assertNull(created.getLeasedBy());
		assertNull(created.getLeaseExpiresAt());
	}

	@Override
	public void updateElement(PipelineNodeTask element) {
		element.setState("COMPLETED");
		element.setAttempt(1);
		element.setDurationMs(1234L);
		element.setOutputs(new JsonObject().put("sha512", "deadbeef"));
	}

	@Override
	public void assertUpdate(PipelineNodeTask updated) {
		assertEquals("COMPLETED", updated.getState());
		assertEquals(1, updated.getAttempt());
		assertEquals(1234L, updated.getDurationMs());
		assertNotNull(updated.getOutputs(), "Outputs must survive the round trip - downstream nodes read them");
		assertEquals("deadbeef", updated.getOutputs().getString("sha512"));
	}

	@Test
	public void testANodeRunsAtMostOncePerItem() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "sha512", "hash-sha512", "COMPLETED");

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

		storeTask(user, item, "sha512", "hash-sha512", "COMPLETED");
		storeTask(user, item, "md5", "hash-md5", "PENDING");

		assertEquals(2, pipelineNodeTaskDao().loadByItem(item.getUuid()).size());
	}

	@Test
	public void testLoadByItemAndNode() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		storeTask(user, item, "sha512", "hash-sha512", "COMPLETED");

		PipelineNodeTask found = pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "sha512");
		assertNotNull(found);
		assertEquals("COMPLETED", found.getState());

		assertNull(pipelineNodeTaskDao().loadByItemAndNode(item.getUuid(), "not-a-node"),
			"An unknown node id must read as absent, not blow up");
	}

	@Test
	public void testExpiredLeasesAreFoundAndLiveOnesAreNot() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		Instant now = Instant.now();

		PipelineNodeTask expired = storeTask(user, item, "expired", "hash-sha512", "RUNNING");
		expired.setLeasedBy("worker-a");
		expired.setLeaseExpiresAt(now.minus(Duration.ofMinutes(5)));
		pipelineNodeTaskDao().update(expired);

		PipelineNodeTask live = storeTask(user, item, "live", "hash-md5", "RUNNING");
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
		storeTask(user, item, "pending", "hash-sha512", "PENDING");

		assertTrue(pipelineNodeTaskDao().loadExpiredLeases(Instant.now(), 100).isEmpty());
	}

	@Test
	public void testExpiredLeaseSweepRespectsItsLimit() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		Instant now = Instant.now();

		for (int i = 0; i < 5; i++) {
			PipelineNodeTask task = storeTask(user, item, "node-" + i, "hash-sha512", "RUNNING");
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

		PipelineNodeTask running = storeTask(user, item, "running", "hash-sha512", "RUNNING");
		running.setLeasedBy("worker-a");
		pipelineNodeTaskDao().update(running);

		// Still stamped with the worker that ran it, but finished. Counting this
		// against the worker's in-flight cap would strangle it over time.
		PipelineNodeTask done = storeTask(user, item, "done", "hash-md5", "COMPLETED");
		done.setLeasedBy("worker-a");
		pipelineNodeTaskDao().update(done);

		assertEquals(1, pipelineNodeTaskDao().countLeasedBy("worker-a"));
		assertEquals(0, pipelineNodeTaskDao().countLeasedBy("worker-b"));
	}

	@Test
	public void testCountByRunAndState() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);

		storeTask(user, item, "a", "hash-sha512", "COMPLETED");
		storeTask(user, item, "b", "hash-md5", "COMPLETED");
		storeTask(user, item, "c", "hash-sha512", "FAILED");

		UUID runUuid = item.getRunUuid();
		assertEquals(2, pipelineNodeTaskDao().countByRunAndState(runUuid, "COMPLETED"));
		assertEquals(1, pipelineNodeTaskDao().countByRunAndState(runUuid, "FAILED"));
	}

	@Test
	public void testDeletingAnItemCascadesToItsTasks() {
		User user = dummyUser();
		PipelineRunItem item = storeItem(user, 0);
		storeTask(user, item, "sha512", "hash-sha512", "COMPLETED");

		pipelineRunItemDao().delete(item.getUuid());

		assertEquals(0, pipelineNodeTaskDao().loadByItem(item.getUuid()).size());
	}

	private PipelineRunItem storeItem(User user, int i) {
		PipelineRun run = PipelineFixtures.createRun(this, user, i);
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), i, "/media/file-" + i + ".mp4");
		pipelineRunItemDao().store(item);
		return item;
	}

	private PipelineNodeTask storeTask(User user, PipelineRunItem item, String nodeId, String nodeKind, String state) {
		PipelineNodeTask task = pipelineNodeTaskDao().createNodeTask(user.getUuid(), item.getUuid(), item.getRunUuid(),
			nodeId, nodeKind);
		task.setState(state);
		pipelineNodeTaskDao().store(task);
		return task;
	}

}
