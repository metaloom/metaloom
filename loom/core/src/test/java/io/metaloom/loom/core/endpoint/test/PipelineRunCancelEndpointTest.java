package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code POST /api/v1/pipelines/:uuid/runs/:runUuid/cancel}.
 *
 * <p>Cancelling a tracked run marks it {@code CANCELLED}; cancelling an unknown run is
 * a 404; cancelling an already-terminal run is a 409; and a caller lacking
 * {@code UPDATE_PIPELINE_RUN} is a 403.</p>
 */
public class PipelineRunCancelEndpointTest {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	private PipelineRunDao runDao() {
		return loom.internal().daos().pipelineRunDao();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	/** A pipeline plus a {@code RUNNING} run row, created directly through the DAOs. */
	private PipelineRun createRunningRun() {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		Pipeline pipeline = loom.internal().daos().pipelineDao().createPipeline(adminUuid, "cancel-test");
		loom.internal().daos().pipelineDao().store(pipeline);
		PipelineRun run = runDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		run.setStatus(PipelineRunStatus.RUNNING);
		runDao().store(run);
		return run;
	}

	private String cancelPath(UUID pipelineUuid, UUID runUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/cancel";
	}

	// ── Tests ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("Cancelling a RUNNING run marks it CANCELLED")
	void testCancelMarksRunCancelled() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, cancelPath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(200, status[0], "Cancelling a running run must succeed");
			PipelineRun reloaded = runDao().load(run.getUuid());
			assertThat(reloaded.getStatus())
.isEqualTo(PipelineRunStatus.CANCELLED);
			assertThat(reloaded.getFinished()).as("finished timestamp must be written").isNotNull();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Cancelling an unknown run is a 404")
	void testCancelUnknownRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, cancelPath(UUID.randomUUID(), UUID.randomUUID()),
				client.getToken(), null, status);

			assertEquals(404, status[0], "An unknown run must yield 404");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Cancelling an already-terminal run is a 409")
	void testCancelTerminalRunIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();
			run.setStatus(PipelineRunStatus.SUCCESS);
			runDao().update(run);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, cancelPath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "A run that already finished must yield 409");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a conflicting cancel must not overwrite the terminal state")
				.isEqualTo(PipelineRunStatus.SUCCESS);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without UPDATE_PIPELINE_RUN is 403")
	void testCancelWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe holds only READ_USER — not UPDATE_PIPELINE_RUN.
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			PipelineRun run = createRunningRun();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, cancelPath(run.getPipelineUuid(), run.getUuid()),
				login.getToken(), null, status);

			assertEquals(403, status[0], "A caller lacking UPDATE_PIPELINE_RUN must be forbidden");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a forbidden cancel must not touch the run")
				.isEqualTo(PipelineRunStatus.RUNNING);
		} finally {
			vertx.close();
		}
	}

	// ── HTTP helper ──────────────────────────────────────────────────────

	private JsonObject httpSend(Vertx vertx, HttpMethod method, String path, String token, JsonObject body,
		int[] statusOut) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		client.request(method, restPort(), "localhost", path)
			.compose(req -> {
				if (token != null) {
					req.putHeader("Authorization", "Bearer " + token);
				}
				if (body != null) {
					req.putHeader("Content-Type", "application/json");
					return req.send(body.encode());
				}
				return req.send();
			})
			.compose(resp -> {
				statusOut[0] = resp.statusCode();
				return resp.body();
			})
			.onSuccess(buf -> {
				try {
					future.complete(buf == null || buf.length() == 0 ? new JsonObject() : new JsonObject(buf));
				} catch (Exception e) {
					future.complete(new JsonObject());
				}
			})
			.onFailure(future::completeExceptionally);

		return future.get(10, TimeUnit.SECONDS);
	}
}
