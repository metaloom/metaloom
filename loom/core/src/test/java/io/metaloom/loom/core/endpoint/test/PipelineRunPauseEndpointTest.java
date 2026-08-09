package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;

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
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
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
	private PipelineRun createRun(PipelineRunStatus status) {
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
			PipelineRun run = createRun(PipelineRunStatus.RUNNING);
			run.setMediaCount(7).setSuccessCount(5).setFailureCount(2);
			runDao().update(run);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(200, status[0], "Pausing a running run must succeed");
			PipelineRun reloaded = runDao().load(run.getUuid());
			assertThat(reloaded.getStatus())
.isEqualTo(PipelineRunStatus.PAUSED);
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
			PipelineRun run = createRun(PipelineRunStatus.PAUSED);

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
			PipelineRun run = createRun(PipelineRunStatus.SUCCESS);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "A run that already finished must yield 409");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a conflicting pause must not overwrite the terminal state")
				.isEqualTo(PipelineRunStatus.SUCCESS);
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
			PipelineRun run = createRun(PipelineRunStatus.RUNNING);

			int[] status = new int[1];
			// Real run, but addressed under a different pipeline.
			httpSend(vertx, HttpMethod.POST, pausePath(UUID.randomUUID(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(404, status[0], "A run addressed under the wrong pipeline must yield 404");
			assertThat(runDao().load(run.getUuid()).getStatus())
.isEqualTo(PipelineRunStatus.RUNNING);
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
			PipelineRun run = createRun(PipelineRunStatus.RUNNING);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				login.getToken(), null, status);

			assertEquals(403, status[0], "A caller lacking UPDATE_PIPELINE_RUN must be forbidden");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a forbidden pause must not touch the run")
				.isEqualTo(PipelineRunStatus.RUNNING);
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
			PipelineRun run = createRun(PipelineRunStatus.PAUSED);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "Resuming a run with no engine must yield 409");
			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("a refused resume must leave the run paused")
				.isEqualTo(PipelineRunStatus.PAUSED);
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
			PipelineRun run = createRun(PipelineRunStatus.RUNNING);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "Resuming a run that is not paused must yield 409");
			assertThat(runDao().load(run.getUuid()).getStatus())
.isEqualTo(PipelineRunStatus.RUNNING);
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
			PipelineRun run = createRun(PipelineRunStatus.CANCELLED);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0], "A terminal run cannot be resumed");
			assertThat(runDao().load(run.getUuid()).getStatus())
.isEqualTo(PipelineRunStatus.CANCELLED);
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
			PipelineRun run = createRun(PipelineRunStatus.PAUSED);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, resumePath(run.getPipelineUuid(), run.getUuid()),
				login.getToken(), null, status);

			assertEquals(403, status[0], "A caller lacking UPDATE_PIPELINE_RUN must be forbidden");
			assertThat(runDao().load(run.getUuid()).getStatus())
.isEqualTo(PipelineRunStatus.PAUSED);
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
			PipelineRun run = createRun(PipelineRunStatus.PAUSED);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				"/api/v1/pipelines/" + run.getPipelineUuid() + "/runs/" + run.getUuid() + "/cancel",
				client.getToken(), null, status);

			assertEquals(200, status[0], "Cancelling a paused run must succeed");
			assertThat(runDao().load(run.getUuid()).getStatus())
.isEqualTo(PipelineRunStatus.CANCELLED);
		} finally {
			vertx.close();
		}
	}

	// ── Event broadcast ──────────────────────────────────────────────────

	@Test
	@DisplayName("Pausing broadcasts RUN_PAUSED on the UI events socket")
	void testPauseBroadcastsRunPaused() throws Exception {
		// A run can be paused from the CLI or a second browser tab, and every other client
		// has to learn about it - otherwise their control keeps offering "Pause" for a run
		// that is already suspended. Loom is the only party that can say so: the pause never
		// reaches a worker, so nothing else could emit this.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun(PipelineRunStatus.RUNNING);

			List<JsonObject> frames = new CopyOnWriteArrayList<>();
			WebSocket ws = connectPipelineEventsWs(vertx);
			ws.textMessageHandler(text -> frames.add(new JsonObject(text)));

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);
			assertEquals(200, status[0]);

			JsonObject frame = awaitFrame(frames, "RUN_PAUSED", run.getUuid());
			assertThat(frame).as("a pause must announce itself on the events socket").isNotNull();
			assertThat(frame.getString("pipelineRunUuid")).isEqualTo(run.getUuid().toString());
			assertThat(frame.getString("pipelineName"))
				.as("the frame must be attributable to a pipeline, since subscribers filter on it")
				.isNotBlank();
			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A refused pause broadcasts nothing")
	void testRefusedPauseBroadcastsNothing() throws Exception {
		// The frame is emitted after the gate is applied, so a 409 must leave the socket
		// silent. Announcing a pause that did not happen would flip every other client's
		// control to Resume for a run that is still running.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun(PipelineRunStatus.SUCCESS);

			List<JsonObject> frames = new CopyOnWriteArrayList<>();
			WebSocket ws = connectPipelineEventsWs(vertx);
			ws.textMessageHandler(text -> frames.add(new JsonObject(text)));

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, pausePath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);
			assertEquals(409, status[0]);

			Thread.sleep(QUIET_WINDOW_MS);
			assertThat(frames.stream().anyMatch(f -> "RUN_PAUSED".equals(f.getString("type"))))
				.as("a refused pause must not be announced")
				.isFalse();
			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Cancelling a run broadcasts PIPELINE_COMPLETED")
	void testCancelBroadcastsPipelineCompleted() throws Exception {
		// engine.cancel() sets runComplete without invoking the completion callbacks, so
		// without an explicit broadcast here a cancelled run would be the one terminal
		// outcome the events socket never reports.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun(PipelineRunStatus.RUNNING);

			List<JsonObject> frames = new CopyOnWriteArrayList<>();
			WebSocket ws = connectPipelineEventsWs(vertx);
			ws.textMessageHandler(text -> frames.add(new JsonObject(text)));

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				"/api/v1/pipelines/" + run.getPipelineUuid() + "/runs/" + run.getUuid() + "/cancel",
				client.getToken(), null, status);
			assertEquals(200, status[0]);

			assertThat(awaitFrame(frames, "PIPELINE_COMPLETED", run.getUuid()))
				.as("a cancel must close the run out on the events socket")
				.isNotNull();
			ws.close();
		} finally {
			vertx.close();
		}
	}

	/** How long to wait before concluding that no frame is coming. */
	private static final long QUIET_WINDOW_MS = 2000;

	/**
	 * Poll the collected frames for one of the given type and run.
	 *
	 * <p>Frames are collected rather than awaited one-by-one because the socket is
	 * multiplexed - a {@code channel:"PROCESSOR"} frame can arrive in between and would
	 * otherwise satisfy a naive "next message" future.</p>
	 *
	 * @return the matching frame, or null if none arrived within the window
	 */
	private JsonObject awaitFrame(List<JsonObject> frames, String type, UUID runUuid) throws Exception {
		long deadline = System.currentTimeMillis() + QUIET_WINDOW_MS;
		while (System.currentTimeMillis() < deadline) {
			for (JsonObject frame : frames) {
				if (type.equals(frame.getString("type")) && runUuid.toString().equals(frame.getString("pipelineRunUuid"))) {
					return frame;
				}
			}
			Thread.sleep(50);
		}
		return null;
	}

	private WebSocket connectPipelineEventsWs(Vertx vertx) throws Exception {
		WebSocketClient wsClient = vertx.createWebSocketClient();
		CompletableFuture<WebSocket> future = new CompletableFuture<>();
		WebSocketConnectOptions opts = new WebSocketConnectOptions()
			.setHost("localhost")
			.setPort(restPort())
			.setURI("/api/v1/pipelines/events/ws");
		wsClient.connect(opts)
			.onSuccess(future::complete)
			.onFailure(future::completeExceptionally);
		return future.get(10, TimeUnit.SECONDS);
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
