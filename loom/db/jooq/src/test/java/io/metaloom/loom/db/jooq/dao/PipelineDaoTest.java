package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
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
