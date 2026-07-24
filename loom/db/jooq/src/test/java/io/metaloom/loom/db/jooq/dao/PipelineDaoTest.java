package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class PipelineDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineDao, Pipeline> {

	/**
	 * Build - but do not store - a pipeline. The CRUD harness stores what this returns, so storing here as well would insert the same row twice.
	 * The version and the latest-version pointer need the pipeline row to exist first, so they are covered by
	 * {@link #testPipelineWithVersion()} rather than from here.
	 */
	@Override
	public Pipeline createElement(User user, int i) {
		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
		pipeline.setMeta(new JsonObject().put("key", "value"));
		return pipeline;
	}

	@Override
	public void assertCreate(Pipeline createdElement) {
		assertNotNull(createdElement.getMeta());
		assertEquals("value", createdElement.getMeta().getString("key"));
	}

	/**
	 * A stored pipeline gets a v1 version and points at it. Covers the cycle between pipeline and pipeline_version, including deleting a pipeline that
	 * carries the pointer.
	 */
	@Test
	public void testPipelineWithVersion() {
		User user = dummyUser();

		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_with_version");
		pipeline.setMeta(new JsonObject().put("key", "value"));
		pipelineDao().store(pipeline);

		PipelineVersion version = pipelineVersionDao().createVersion(
			user.getUuid(),
			pipeline.getUuid(),
			1,
			"pipeline_with_version",
			"Test pipeline",
			new JsonObject().put("nodes", new JsonArray()),
			true,
			0,
			false,
			new JsonObject().put("versionKey", "versionValue"));
		pipelineVersionDao().store(version);

		pipeline.setLatestVersionUuid(version.getUuid());
		pipelineDao().update(pipeline);

		Pipeline loaded = pipelineDao().load(pipeline.getUuid());
		assertNotNull(loaded);
		assertEquals(version.getUuid(), loaded.getLatestVersionUuid());

		// The pointer must not block the delete - see V2.49.
		pipelineDao().delete(pipeline.getUuid());
		assertNull(pipelineDao().load(pipeline.getUuid()));
	}

	/**
	 * Deleting a pipeline cascades to its versions (V2.30).
	 */
	@Test
	public void testDeletingAPipelineCascadesToItsVersions() {
		User user = dummyUser();

		Pipeline pipeline = pipelineDao().createPipeline(user, "cascade_versions");
		pipelineDao().store(pipeline);
		storeVersion(user, pipeline.getUuid(), 1);
		storeVersion(user, pipeline.getUuid(), 2);
		assertEquals(2, pipelineVersionDao().loadByPipeline(pipeline.getUuid()).size());

		pipelineDao().delete(pipeline.getUuid());

		assertEquals(0, pipelineVersionDao().loadByPipeline(pipeline.getUuid()).size(),
			"Deleting a pipeline must cascade to its versions");
	}

	/**
	 * Deleting a pipeline cascades transitively through the whole run chain: run (V2.29) -> run item -> node task (V2.31).
	 */
	@Test
	public void testDeletingAPipelineCascadesThroughTheRunChain() {
		User user = dummyUser();

		PipelineRun run = PipelineFixtures.createRun(this, user, 0);
		UUID pipelineUuid = run.getPipelineUuid();
		UUID runUuid = run.getUuid();
		PipelineRunItem item = storeItem(user, runUuid, 1);
		storeTask(user, item, "sha512", "hash-sha512");

		// The chain exists before the delete.
		assertEquals(1, pipelineRunDao().loadByPipeline(pipelineUuid).size());
		assertEquals(1, pipelineRunItemDao().loadByRun(runUuid).size());
		assertEquals(1, pipelineNodeTaskDao().loadByRun(runUuid).size());

		pipelineDao().delete(pipelineUuid);

		assertNull(pipelineDao().load(pipelineUuid));
		assertEquals(0, pipelineRunDao().loadByPipeline(pipelineUuid).size(), "The run must be gone");
		assertEquals(0, pipelineRunItemDao().loadByRun(runUuid).size(), "The run item must be gone");
		assertEquals(0, pipelineNodeTaskDao().loadByRun(runUuid).size(), "The node task must be gone");
	}

	/**
	 * Deleting a run removes its node tasks. Each task references the run both directly (run_uuid) and through its item (item_uuid -> run), so this
	 * pins the direct run FK cascade from V2.31 - the item cascade alone is already covered by
	 * PipelineNodeTaskDaoTest.testDeletingAnItemCascadesToItsTasks.
	 */
	@Test
	public void testDeletingARunCascadesToItsNodeTasks() {
		User user = dummyUser();

		PipelineRun run = PipelineFixtures.createRun(this, user, 0);
		UUID runUuid = run.getUuid();
		PipelineRunItem item = storeItem(user, runUuid, 1);
		storeTask(user, item, "sha512", "hash-sha512");
		assertEquals(1, pipelineNodeTaskDao().loadByRun(runUuid).size());

		pipelineRunDao().delete(runUuid);

		assertEquals(0, pipelineNodeTaskDao().loadByRun(runUuid).size(), "The node task must be gone with the run");
		assertEquals(0, pipelineRunItemDao().loadByRun(runUuid).size(), "The run item must be gone with the run");
	}

	/**
	 * Negative control: deleting one pipeline's full chain leaves another pipeline's versions, runs, items and tasks untouched.
	 */
	@Test
	public void testDeletingAPipelineLeavesOtherPipelinesIntact() {
		User user = dummyUser();

		PipelineRun runA = PipelineFixtures.createRun(this, user, 0);
		PipelineRunItem itemA = storeItem(user, runA.getUuid(), 1);
		storeTask(user, itemA, "sha512", "hash-sha512");

		PipelineRun runB = PipelineFixtures.createRun(this, user, 1);
		UUID pipelineB = runB.getPipelineUuid();
		UUID runBUuid = runB.getUuid();
		PipelineRunItem itemB = storeItem(user, runBUuid, 1);
		storeTask(user, itemB, "sha512", "hash-sha512");

		pipelineDao().delete(runA.getPipelineUuid());

		assertNull(pipelineDao().load(runA.getPipelineUuid()));
		assertNotNull(pipelineDao().load(pipelineB), "Deleting one pipeline must not touch another");
		assertEquals(1, pipelineVersionDao().loadByPipeline(pipelineB).size());
		assertEquals(1, pipelineRunDao().loadByPipeline(pipelineB).size());
		assertEquals(1, pipelineRunItemDao().loadByRun(runBUuid).size());
		assertEquals(1, pipelineNodeTaskDao().loadByRun(runBUuid).size());
	}

	private PipelineVersion storeVersion(User user, UUID pipelineUuid, int versionNumber) {
		PipelineVersion version = pipelineVersionDao().createVersion(
			user.getUuid(),
			pipelineUuid,
			versionNumber,
			"v" + versionNumber,
			"Test version " + versionNumber,
			new JsonObject().put("nodes", new JsonArray()),
			true,
			0,
			false,
			null);
		pipelineVersionDao().store(version);
		return version;
	}

	private PipelineRunItem storeItem(User user, UUID runUuid, long seq) {
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), runUuid, seq, "/media/" + seq + ".mp4");
		pipelineRunItemDao().store(item);
		return item;
	}

	private PipelineNodeTask storeTask(User user, PipelineRunItem item, String nodeId, String nodeKind) {
		PipelineNodeTask task = pipelineNodeTaskDao().createNodeTask(user.getUuid(), item.getUuid(), item.getRunUuid(), nodeId, nodeKind);
		pipelineNodeTaskDao().store(task);
		return task;
	}

	@Override
	public PipelineDao getDao() {
		return pipelineDao();
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}

	@Override
	public void updateElement(Pipeline element) {
		element.setMeta(new JsonObject().put("updated", true));
	}

	@Override
	public void assertUpdate(Pipeline updatedPipeline) {
		assertNotNull(updatedPipeline.getMeta());
		assertEquals(true, updatedPipeline.getMeta().getBoolean("updated"));
	}

}
