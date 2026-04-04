package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class PipelineDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineDao, Pipeline> {

	@Override
	public Pipeline createElement(User user, int i) {
		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
		pipeline.setDescription("Test pipeline " + i);
		pipeline.setDefinition(new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()));
		pipeline.setEnabled(true);
		pipeline.setPriority(i);
		pipeline.setDryRun(false);
		return pipeline;
	}

	@Override
	public void assertCreate(Pipeline createdElement) {
		assertEquals("pipeline_0", createdElement.getName());
		assertEquals("Test pipeline 0", createdElement.getDescription());
		assertNotNull(createdElement.getDefinition());
		assertEquals(true, createdElement.isEnabled());
		assertEquals(0, createdElement.getPriority());
		assertEquals(false, createdElement.isDryRun());
	}

	@Override
	public PipelineDao getDao() {
		return pipelineDao();
	}

	@Override
	public void updateElement(Pipeline element) {
		element.setName("updated-pipeline");
		element.setDescription("Updated description");
		element.setPriority(99);
		element.setEnabled(false);
	}

	@Override
	public void assertUpdate(Pipeline updatedPipeline) {
		assertEquals("updated-pipeline", updatedPipeline.getName());
		assertEquals("Updated description", updatedPipeline.getDescription());
		assertEquals(99, updatedPipeline.getPriority());
		assertEquals(false, updatedPipeline.isEnabled());
	}

}
