package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.metaloom.loom.rest.service.impl.PipelineRunTracker;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies that a {@code PIPELINE_RUN_COMPLETED} frame from a processor closes
 * out the corresponding {@code pipeline_run} row.
 *
 * <p>Before this was wired, {@code ProcessorEndpoint.handlePipelineRunCompleted}
 * was a TODO that only logged, so every run stayed at {@code RUNNING} forever and
 * the four counter columns were never written.</p>
 */
public class PipelineRunCompletionEndpointTest {

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

	// ── Fixtures ─────────────────────────────────────────────────────────

	/**
	 * Create a pipeline plus a {@code RUNNING} run row directly through the DAOs —
	 * the REST run endpoint needs a connected processor, which this test does not
	 * have. We only care about the completion half of the flow here.
	 */
	private PipelineRun createRunningRun() {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();

		// A pipeline row is enough — pipeline_run only FKs to pipeline(uuid), and
		// this test exercises the completion path rather than the version model.
		Pipeline pipeline = loom.internal().daos().pipelineDao().createPipeline(adminUuid, "completion-test");
		loom.internal().daos().pipelineDao().store(pipeline);

		PipelineRun run = runDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		run.setStatus(PipelineRunStatus.RUNNING);
		runDao().store(run);
		return run;
	}

	private WebSocket connectAndRegister(Vertx vertx) throws Exception {
		WebSocketClient wsClient = vertx.createWebSocketClient();
		CompletableFuture<WebSocket> connected = new CompletableFuture<>();
		wsClient.connect(new WebSocketConnectOptions()
			.setHost("localhost")
			.setPort(restPort())
			.setURI("/api/v1/processors/ws"))
			.onSuccess(connected::complete)
			.onFailure(connected::completeExceptionally);
		WebSocket ws = connected.get(10, TimeUnit.SECONDS);

		CompletableFuture<JsonObject> ack = new CompletableFuture<>();
		ws.textMessageHandler(text -> ack.complete(new JsonObject(text)));
		ws.writeTextMessage(new JsonObject()
			.put("type", "REGISTER")
			.put("body", new JsonObject()
				.put("nodeId", "cortex-completion-test")
				.put("name", "cortex-completion-test")
				.put("host", "localhost:9090")
				.put("priority", 1)
				.put("capabilities", new JsonArray().add("CPU")))
			.encode());
		assertThat(ack.get(10, TimeUnit.SECONDS).getString("type")).isEqualTo("REGISTERED");
		return ws;
	}

	private void sendCompletion(WebSocket ws, JsonObject body) throws Exception {
		ws.writeTextMessage(new JsonObject()
			.put("type", "PIPELINE_RUN_COMPLETED")
			.put("body", body)
			.encode());
		// The handler persists synchronously on the WS thread; give it a moment
		// to land before reading the row back.
		Thread.sleep(500);
	}

	// ── Tests ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("A fully successful completion closes the run as SUCCESS with counters")
	void testCompletionMarksRunSuccess() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();
			assertThat(run.getStatus())
.isEqualTo(PipelineRunStatus.RUNNING);

			WebSocket ws = connectAndRegister(vertx);
			sendCompletion(ws, new JsonObject()
				.put("pipelineName", "completion-test")
				.put("pipelineRunUuid", run.getUuid().toString())
				.put("durationMs", 4321L)
				.put("mediaCount", 10)
				.put("successCount", 10)
				.put("failureCount", 0)
				.put("skippedCount", 0)
				.put("message", "Processed 10 media items"));

			PipelineRun reloaded = runDao().load(run.getUuid());
			assertThat(reloaded.getStatus())
.isEqualTo(PipelineRunStatus.SUCCESS);
			assertThat(reloaded.getFinished()).as("finished timestamp must be written").isNotNull();
			assertThat(reloaded.getDurationMs()).isEqualTo(4321L);
			assertThat(reloaded.getMediaCount()).isEqualTo(10);
			assertThat(reloaded.getSuccessCount()).isEqualTo(10);
			assertThat(reloaded.getFailureCount()).isZero();
			assertThat(reloaded.getSkippedCount()).isZero();

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A partially failed completion closes the run as PARTIAL")
	void testCompletionMarksRunPartial() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();

			WebSocket ws = connectAndRegister(vertx);
			sendCompletion(ws, new JsonObject()
				.put("pipelineName", "completion-test")
				.put("pipelineRunUuid", run.getUuid().toString())
				.put("durationMs", 999L)
				.put("mediaCount", 10)
				.put("successCount", 6)
				.put("failureCount", 3)
				.put("skippedCount", 1));

			PipelineRun reloaded = runDao().load(run.getUuid());
			assertThat(reloaded.getStatus())
.isEqualTo(PipelineRunStatus.PARTIAL);
			assertThat(reloaded.getSuccessCount()).isEqualTo(6);
			assertThat(reloaded.getFailureCount()).isEqualTo(3);
			assertThat(reloaded.getSkippedCount()).isEqualTo(1);

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A completion where everything failed closes the run as FAILED")
	void testCompletionMarksRunFailed() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();

			WebSocket ws = connectAndRegister(vertx);
			sendCompletion(ws, new JsonObject()
				.put("pipelineName", "completion-test")
				.put("pipelineRunUuid", run.getUuid().toString())
				.put("mediaCount", 4)
				.put("successCount", 0)
				.put("failureCount", 4)
				.put("skippedCount", 0));

			assertThat(runDao().load(run.getUuid()).getStatus())
.isEqualTo(PipelineRunStatus.FAILED);
			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A run already in a terminal state is not overwritten by a late report")
	void testTerminalRunIsNotOverwritten() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();
			run.setStatus(PipelineRunStatus.SUCCESS).setMediaCount(7);
			runDao().update(run);

			WebSocket ws = connectAndRegister(vertx);
			// A late watchdog or duplicate frame reporting total failure.
			sendCompletion(ws, new JsonObject()
				.put("pipelineName", "completion-test")
				.put("pipelineRunUuid", run.getUuid().toString())
				.put("mediaCount", 1)
				.put("failureCount", 1));

			PipelineRun reloaded = runDao().load(run.getUuid());
			assertThat(reloaded.getStatus())
				.as("the first terminal verdict must win")
				.isEqualTo("SUCCESS");
			assertThat(reloaded.getMediaCount()).isEqualTo(7);

			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A completion with no run id is ignored rather than throwing")
	void testUntrackedCompletionIsIgnored() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();

			WebSocket ws = connectAndRegister(vertx);
			// An offline/CLI Cortex reports completion without a run correlation.
			sendCompletion(ws, new JsonObject()
				.put("pipelineName", "completion-test")
				.put("durationMs", 100L)
				.put("mediaCount", 3));

			assertThat(runDao().load(run.getUuid()).getStatus())
				.as("an untracked completion must not touch unrelated runs")
				.isEqualTo("RUNNING");

			// The socket must still be usable afterwards.
			assertThat(ws.isClosed()).isFalse();
			ws.close();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Failing a run that already completed does not overwrite its result")
	void testWatchdogCannotOverwriteCompletedRun() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRunningRun();

			PipelineRunTracker tracker = new PipelineRunTracker(runDao());
			assertThat(tracker.complete(run.getUuid(), 500L, 5, 5, 0, 0)).isTrue();

			// A late watchdog arriving after the real result must be a no-op.
			assertThat(tracker.fail(run.getUuid(), "Work order timed out")).isFalse();

			PipelineRun reloaded = runDao().load(run.getUuid());
			assertThat(reloaded.getStatus())
.isEqualTo(PipelineRunStatus.SUCCESS);
			assertThat(reloaded.getMediaCount()).isEqualTo(5);
			assertThat(reloaded.getErrorMessage()).isNull();
		}
	}

	@Test
	@DisplayName("A completion referencing an unknown or malformed run id is survivable")
	void testUnknownRunIdIsSurvivable() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			WebSocket ws = connectAndRegister(vertx);

			sendCompletion(ws, new JsonObject()
				.put("pipelineName", "completion-test")
				.put("pipelineRunUuid", UUID.randomUUID().toString())
				.put("mediaCount", 1));
			assertThat(ws.isClosed()).isFalse();

			sendCompletion(ws, new JsonObject()
				.put("pipelineName", "completion-test")
				.put("pipelineRunUuid", "not-a-uuid")
				.put("mediaCount", 1));
			assertThat(ws.isClosed()).isFalse();

			ws.close();
		} finally {
			vertx.close();
		}
	}
}
