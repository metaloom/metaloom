package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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
 * Verifies {@code POST /api/v1/pipelines/:uuid/runs/:runUuid/{pause,resume}}.
 *
 * <p>Pause is reversible and non-terminal, which is what separates it from cancel. The
 * cases that matter are the guards: you cannot pause what has finished, cannot pause twice,
 * and cannot resume a run whose engine no longer exists - the last one because flipping a
 * dead row back to {@code RUNNING} would produce a run nothing will ever advance.</p>
 *
 * <p>These tests create run rows directly through the DAOs, so there is never a live engine
 * in the registry. That is deliberate: it is exactly the "lost to a restart" shape, and it
 * lets the row-level guards be tested without standing up a processor.</p>
 */
public class PipelineRunPauseEndpointTest {

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

	/** A pipeline plus a run row in the given status, created directly through the DAOs. */
	private PipelineRun createRun(String status) {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		Pipeline pipeline = loom.internal().daos().pipelineDao().createPipeline(adminUuid, "pause-test-" + UUID.randomUUID());
		loom.internal().daos().pipelineDao().store(pipeline);
		PipelineRun run = runDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		run.setStatus(status);
		runDao().store(run);
		return run;
	}

	private String pausePath(UUID pipelineUuid, UUID runUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/pause";
	}

	private String resumePath(UUID pipelineUuid, UUID runUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/resume";
	}

	// ── Pause ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("Pausing a RUNNING run marks it PAUSED and leaves its counters alone")
	void testPauseMarksRunPaused() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");
			run.setMediaCount(7).setSuccessCount(5).setFailureCount(2);
			runDao().update(run);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(200, status[0], "Pausing a running run must succeed");
			PipelineRun reloaded = runDao().load(run.getUuid());
			assertThat(reloaded.getStatus()).isEqualTo("PAUSED");
			assertThat(reloaded.getFinished()).as("a pause is not a completion").isNull();
			// The tracker's terminal path zeroes these; a pause must not.
			assertThat(reloaded.getMediaCount()).as("counters survive a pause").isEqualTo(7);
			assertThat(reloaded.getSuccessCount()).isEqualTo(5);
			assertThat(reloaded.getFailureCount()).isEqualTo(2);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Pausing an already-paused run is a 409")
	void testPauseAlreadyPausedIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("PAUSED");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "A run that is already paused must yield 409");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Pausing an already-terminal run is a 409 and does not overwrite it")
	void testPauseTerminalRunIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("SUCCESS");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "A run that already finished must yield 409");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a conflicting pause must not overwrite the terminal state")
				.isEqualTo("SUCCESS");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Pausing an unknown run is a 404")
	void testPauseUnknownRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(UUID.randomUUID(), UUID.randomUUID()),
				client.getToken(), null, status);

			assertEquals(404, status[0], "An unknown run must yield 404");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Pausing a run through the wrong pipeline is a 404")
	void testPauseWrongPipelineIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			// Real run, but addressed under a different pipeline.
			httpSend(vertx, HttpMethod.POST, pausePath(UUID.randomUUID(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(404, status[0], "A run addressed under the wrong pipeline must yield 404");
			assertThat(runDao().load(run.getUuid()).getStatus()).isEqualTo("RUNNING");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without UPDATE_PIPELINE_RUN cannot pause")
	void testPauseWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe holds only READ_USER - not UPDATE_PIPELINE_RUN.
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				login.getToken(), null, status);

			assertEquals(403, status[0], "A caller lacking UPDATE_PIPELINE_RUN must be forbidden");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a forbidden pause must not touch the run")
				.isEqualTo("RUNNING");
		} finally {
			vertx.close();
		}
	}

	// ── Resume ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("Resuming a run with no live engine is a 409, not a silent revival")
	void testResumeWithoutLiveEngineIsConflict() throws Exception {
		// This run was created straight through the DAOs, so nothing is registered for it -
		// the same shape as a run whose engine was lost to a restart. Moving it back to
		// RUNNING would create a run that nothing advances, so it must be refused.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("PAUSED");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "Resuming a run with no engine must yield 409");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a refused resume must leave the run paused")
				.isEqualTo("PAUSED");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Resuming a run that is not paused is a 409")
	void testResumeNonPausedRunIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "Resuming a run that is not paused must yield 409");
			assertThat(runDao().load(run.getUuid()).getStatus()).isEqualTo("RUNNING");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Resuming a terminal run is a 409")
	void testResumeTerminalRunIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("CANCELLED");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "A terminal run cannot be resumed");
			assertThat(runDao().load(run.getUuid()).getStatus()).isEqualTo("CANCELLED");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Resuming an unknown run is a 404")
	void testResumeUnknownRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(UUID.randomUUID(), UUID.randomUUID()),
				client.getToken(), null, status);

			assertEquals(404, status[0], "An unknown run must yield 404");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without UPDATE_PIPELINE_RUN cannot resume")
	void testResumeWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			PipelineRun run = createRun("PAUSED");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				login.getToken(), null, status);

			assertEquals(403, status[0], "A caller lacking UPDATE_PIPELINE_RUN must be forbidden");
			assertThat(runDao().load(run.getUuid()).getStatus()).isEqualTo("PAUSED");
		} finally {
			vertx.close();
		}
	}

	// ── Cancel interaction ───────────────────────────────────────────────

	@Test
	@DisplayName("A paused run can still be cancelled")
	void testCancelWhilePaused() throws Exception {
		// PAUSED is non-terminal, so the cancel guard must let it through - otherwise an
		// operator who paused a run could never get rid of it.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("PAUSED");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				"/api/v1/pipelines/" + run.getPipelineUuid() + "/runs/" + run.getUuid() + "/cancel",
				client.getToken(), null, status);

			assertEquals(200, status[0], "Cancelling a paused run must succeed");
			assertThat(runDao().load(run.getUuid()).getStatus()).isEqualTo("CANCELLED");
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
