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

public class PipelineVersionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineVersionDao, PipelineVersion> {

	@Override
	public PipelineVersion createElement(User user, int i) {
		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
		pipeline.setMeta(new JsonObject().put("key", "value"));
		pipelineDao().store(pipeline);

		PipelineVersion version = pipelineVersionDao().createVersion(
			user.getUuid(),
			pipeline.getUuid(),
			i + 1,
			"version_" + i,
			"Test version " + i,
			new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()),
			true,
			i,
			false,
			new JsonObject().put("versionKey", "versionValue")
		);
		return version;
	}

	@Override
	public void assertCreate(PipelineVersion createdElement) {
		assertEquals("version_0", createdElement.getName());
		assertEquals("Test version 0", createdElement.getDescription());
		assertNotNull(createdElement.getDefinition());
		assertEquals(true, createdElement.isEnabled());
		assertEquals(0, createdElement.getPriority());
		assertEquals(false, createdElement.isDryRun());
		assertNotNull(createdElement.getMeta());
		assertEquals(1, createdElement.getVersionNumber());
		assertNotNull(createdElement.getPipelineUuid());
	}

	@Override
	public PipelineVersionDao getDao() {
		return pipelineVersionDao();
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}

	@Override
	public void updateElement(PipelineVersion element) {
		element.setName("updated-version");
		element.setDescription("Updated description");
		element.setPriority(99);
		element.setEnabled(false);
	}

	@Override
	public void assertUpdate(PipelineVersion updatedElement) {
		assertEquals("updated-version", updatedElement.getName());
		assertEquals("Updated description", updatedElement.getDescription());
		assertEquals(99, updatedElement.getPriority());
		assertEquals(false, updatedElement.isEnabled());
	}

}