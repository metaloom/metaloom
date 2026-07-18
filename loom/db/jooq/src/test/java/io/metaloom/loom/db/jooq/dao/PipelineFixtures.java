package io.metaloom.loom.db.jooq.dao;

import io.metaloom.loom.db.dagger.DaoProvider;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Builds the pipeline → version → run chain that execution-state rows hang off.
 *
 * <p>{@code pipeline_run_item} and {@code pipeline_node_task} both sit at the end of
 * a four-table foreign key chain, so every test that touches them has to construct
 * the same scaffolding. Keeping it here stops that setup from being copied - and
 * quietly diverging - across test classes.</p>
 */
public final class PipelineFixtures {

	private PipelineFixtures() {
	}

	/**
	 * Create and store a pipeline, its v1 version, and a run against it.
	 *
	 * @param daos the provider to create through
	 * @param user the owner
	 * @param i    discriminator, so repeated calls produce distinct pipelines
	 * @return the stored run
	 */
	public static PipelineRun createRun(DaoProvider daos, User user, int i) {
		Pipeline pipeline = daos.pipelineDao().createPipeline(user, "pipeline_" + i);
		daos.pipelineDao().store(pipeline);

		PipelineVersion version = daos.pipelineVersionDao().createVersion(
			user.getUuid(),
			pipeline.getUuid(),
			1,
			"pipeline_" + i,
			"Test pipeline " + i,
			new JsonObject().put("nodes", new JsonArray()),
			true,
			i,
			false,
			null);
		daos.pipelineVersionDao().store(version);

		pipeline.setLatestVersionUuid(version.getUuid());
		daos.pipelineDao().update(pipeline);

		PipelineRun run = daos.pipelineRunDao().createPipelineRun(user.getUuid(), pipeline.getUuid(), 1);
		run.setStatus("RUNNING");
		daos.pipelineRunDao().store(run);
		return run;
	}

	/**
	 * Flatten an exception chain into one searchable string.
	 *
	 * <p>jOOQ wraps the driver's exception, so the constraint name a test wants to
	 * assert on sits one or two levels down rather than in {@code getMessage()}.</p>
	 *
	 * @param t the thrown exception
	 * @return the concatenated messages of the whole chain, never null
	 */
	public static String rootCauseMessage(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable current = t; current != null; current = current.getCause()) {
			sb.append(current.getMessage()).append(" | ");
			if (current.getCause() == current) {
				break;
			}
		}
		return sb.toString();
	}

}
