package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What happens to an ad-hoc run when Loom restarts.
 *
 * <p>
 * A process that dies never moves its runs out of {@code RUNNING}, so on the next start that status
 * means "was in progress when we stopped" and recovery rebuilds the graph. For a catalog run the graph
 * comes from the {@code pipeline_version} row; an ad-hoc run has none, and the version lookup would
 * return null - which recovery treats as "the definition is gone" and <b>fails the run</b>. Every
 * ad-hoc run would therefore be killed by the next restart. The definition travels on the run row
 * precisely so it does not have to be.
 * </p>
 */
public class NodeRunRecoveryTest extends AbstractEndpointTest {

	private JsonObject definition() {
		return new JsonObject()
			.put("version", 1)
			.put("name", "recovered run")
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "loom-fetch").put("source", true))
				.add(new JsonObject().put("id", "n1").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "e1")
					.put("source", "src").put("sourcePort", "media")
					.put("target", "n1").put("targetPort", "media")));
	}

	private void recover() {
		loom.internal().boot().getRestService().getRunRecovery().recoverAll();
	}

	@Test
	@DisplayName("An in-flight ad-hoc run is rebuilt from its own definition, not written off")
	void testAdhocRunIsRecoveredFromItsDefinition() {
		PipelineRun run = daos().pipelineRunDao().createAdhocRun(adminUuid(), definition());
		run.setStatus(PipelineRunStatus.RUNNING);
		daos().pipelineRunDao().store(run);
		UUID runUuid = run.getUuid();

		recover();

		PipelineRun reloaded = daos().pipelineRunDao().load(runUuid);
		// The run may well end up FAILED here - no worker is connected, so its one node cannot be
		// dispatched - but it must not be failed for the wrong reason.
		assertThat(reloaded.getErrorMessage() == null ? "" : reloaded.getErrorMessage())
			.as("an ad-hoc run has no pipeline version and must not be judged as if it lost one")
			.doesNotContain("Pipeline version");
	}

	@Test
	@DisplayName("An ad-hoc run whose definition is missing is failed, saying so")
	void testAdhocRunWithoutADefinitionIsFailedHonestly() {
		PipelineRun run = daos().pipelineRunDao().createAdhocRun(adminUuid(), definition());
		// Meta wiped: whatever caused it, there is no graph to resume against and leaving the run
		// RUNNING forever would be worse than closing it.
		run.setMeta(new JsonObject());
		run.setStatus(PipelineRunStatus.RUNNING);
		daos().pipelineRunDao().store(run);
		UUID runUuid = run.getUuid();

		recover();

		PipelineRun reloaded = daos().pipelineRunDao().load(runUuid);
		assertThat(reloaded.getStatus()).isEqualTo(PipelineRunStatus.FAILED);
		assertThat(reloaded.getErrorMessage()).contains("no definition");
		assertNotEquals(PipelineRunStatus.RUNNING, reloaded.getStatus(), "a run nothing can advance must not stay RUNNING");
	}

}
