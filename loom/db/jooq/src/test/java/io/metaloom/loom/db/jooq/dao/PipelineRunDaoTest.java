package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class PipelineRunDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineRunDao, PipelineRun> {

	@Override
	public PipelineRun createElement(User user, int i) {
		Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
		pipeline.setDefinition(new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()));
		pipeline.setEnabled(true);
		pipelineDao().store(pipeline);

		PipelineRun run = pipelineRunDao().createPipelineRun(user.getUuid(), pipeline.getUuid(), 1);
		run.setStatus("SUCCESS");
		run.setMediaCount(100);
		run.setSuccessCount(95);
		run.setFailureCount(3);
		run.setSkippedCount(2);
		run.setDryRun(false);
		run.setDurationMs(45000L);
		run.setErrorMessage(null);
		return run;
	}

	@Override
	public void assertCreate(PipelineRun createdElement) {
		assertEquals("SUCCESS", createdElement.getStatus());
		assertEquals(100, createdElement.getMediaCount());
		assertEquals(95, createdElement.getSuccessCount());
		assertEquals(3, createdElement.getFailureCount());
		assertEquals(2, createdElement.getSkippedCount());
		assertEquals(false, createdElement.isDryRun());
		assertEquals(45000L, createdElement.getDurationMs());
		assertNull(createdElement.getErrorMessage());
		assertNotNull(createdElement.getPipelineUuid());
		assertEquals(1, createdElement.getPipelineVersion());
	}

	@Override
	public PipelineRunDao getDao() {
		return pipelineRunDao();
	}

	@Override
	public void updateElement(PipelineRun element) {
		element.setStatus("FAILED");
		element.setMediaCount(200);
		element.setSuccessCount(150);
		element.setFailureCount(50);
		element.setSkippedCount(0);
		element.setDurationMs(90000L);
		element.setErrorMessage("Processing failed");
	}

	@Override
	public void assertUpdate(PipelineRun updatedElement) {
		assertEquals("FAILED", updatedElement.getStatus());
		assertEquals(200, updatedElement.getMediaCount());
		assertEquals(150, updatedElement.getSuccessCount());
		assertEquals(50, updatedElement.getFailureCount());
		assertEquals(0, updatedElement.getSkippedCount());
		assertEquals(90000L, updatedElement.getDurationMs());
		assertEquals("Processing failed", updatedElement.getErrorMessage());
	}

}