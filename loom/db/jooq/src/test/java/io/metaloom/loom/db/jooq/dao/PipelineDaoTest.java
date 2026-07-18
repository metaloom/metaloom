package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class PipelineDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineDao, Pipeline> {

	@Override
	public Pipeline createElement(User user, int i) {
		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
		pipeline.setMeta(new JsonObject().put("key", "value"));
		pipelineDao().store(pipeline);

		// Create v1 version
		PipelineVersion version = pipelineVersionDao().createVersion(
			user.getUuid(),
			pipeline.getUuid(),
			1,
			"pipeline_" + i,
			"Test pipeline " + i,
			new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()),
			true,
			i,
			false,
			new JsonObject().put("versionKey", "versionValue")
		);
		pipelineVersionDao().store(version);

		// Update pipeline with latest version reference
		pipeline.setLatestVersionUuid(version.getUuid());
		pipelineDao().update(pipeline);

		return pipeline;
	}

	@Override
	public void assertCreate(Pipeline createdElement) {
		assertNotNull(createdElement.getLatestVersionUuid());
		assertNotNull(createdElement.getMeta());
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
