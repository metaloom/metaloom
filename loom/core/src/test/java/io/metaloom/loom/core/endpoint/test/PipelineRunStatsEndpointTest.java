package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunDayStatsRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunStatsResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code GET /api/v1/pipelines/runs/stats}.
 *
 * <p>The endpoint aggregates run counters across all pipelines into daily buckets and
 * zero-fills days without runs. A caller lacking {@code READ_PIPELINE_RUN} is a 403.</p>
 */
public class PipelineRunStatsEndpointTest {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	private PipelineDao pipelineDao() {
		return loom.internal().daos().pipelineDao();
	}

	private PipelineRunDao runDao() {
		return loom.internal().daos().pipelineRunDao();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	private UUID createPipeline(String name) {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		Pipeline pipeline = pipelineDao().createPipeline(adminUuid, name + "-" + UUID.randomUUID());
		pipelineDao().store(pipeline);
		return pipeline.getUuid();
	}

	/** Persist a run of the given pipeline started at noon (UTC) of the given day. */
	private PipelineRun createRunOn(UUID pipelineUuid, LocalDate day, PipelineRunStatus status, int success, int failure, int skipped) {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		PipelineRun run = runDao().createPipelineRun(adminUuid, pipelineUuid, 1);
		run.setStatus(status);
		run.setMediaCount(success + failure + skipped);
		run.setSuccessCount(success);
		run.setFailureCount(failure);
		run.setSkippedCount(skipped);
		run.setStarted(day.atTime(12, 0).toInstant(ZoneOffset.UTC));
		runDao().store(run);
		return run;
	}

	private PipelineRunDayStatsRecord bucket(PipelineRunStatsResponse response, LocalDate day) {
		return response.getDaily().stream()
			.filter(b -> day.toString().equals(b.getDate()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No bucket for " + day + " in " + response.getDaily().size() + " buckets"));
	}

	// ── Tests ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("Run counters of all pipelines aggregate into zero-filled daily buckets")
	void testStatsAggregation() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipelineA = createPipeline("stats-a");
			UUID pipelineB = createPipeline("stats-b");

			LocalDate today = LocalDate.now();
			LocalDate yesterday = today.minusDays(1);
			LocalDate threeDaysAgo = today.minusDays(3);

			createRunOn(pipelineA, yesterday, PipelineRunStatus.SUCCESS, 5, 1, 2);
			createRunOn(pipelineB, yesterday, PipelineRunStatus.PARTIAL, 10, 0, 0);
			createRunOn(pipelineA, threeDaysAgo, PipelineRunStatus.FAILED, 0, 3, 1);

			PipelineRunStatsResponse response = client.loadPipelineRunStats().sync().body();

			// The default window is 14 zero-filled buckets ending today, oldest first.
			assertThat(response.getDaily()).hasSize(14);
			assertEquals(today.minusDays(13).toString(), response.getDaily().get(0).getDate());
			assertEquals(today.toString(), response.getDaily().get(13).getDate());

			PipelineRunDayStatsRecord y = bucket(response, yesterday);
			assertEquals(2, y.getRunCount(), "Runs of both pipelines must aggregate into the same bucket");
			assertEquals(15, y.getSuccessCount());
			assertEquals(1, y.getFailureCount());
			assertEquals(2, y.getSkippedCount());

			PipelineRunDayStatsRecord t3 = bucket(response, threeDaysAgo);
			assertEquals(1, t3.getRunCount());
			assertEquals(0, t3.getSuccessCount());
			assertEquals(3, t3.getFailureCount());
			assertEquals(1, t3.getSkippedCount());

			// A day without runs is present but zero-filled.
			PipelineRunDayStatsRecord empty = bucket(response, today.minusDays(2));
			assertEquals(0, empty.getRunCount());
			assertEquals(0, empty.getSuccessCount());
		}
	}

	@Test
	@DisplayName("Without any runs all buckets are zero-filled")
	void testStatsEmpty() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			PipelineRunStatsResponse response = client.loadPipelineRunStats().sync().body();
			assertThat(response.getDaily()).hasSize(14);
			assertThat(response.getDaily()).allMatch(b -> b.getRunCount() == 0 && b.getSuccessCount() == 0
				&& b.getFailureCount() == 0 && b.getSkippedCount() == 0);
		}
	}

	@Test
	@DisplayName("The days query parameter shrinks the window")
	void testStatsDaysParameter() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID pipeline = createPipeline("stats-days");
			createRunOn(pipeline, LocalDate.now().minusDays(1), PipelineRunStatus.SUCCESS, 4, 0, 0);
			createRunOn(pipeline, LocalDate.now().minusDays(10), PipelineRunStatus.SUCCESS, 7, 0, 0);

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.GET, "/api/v1/pipelines/runs/stats?days=7", client.getToken(), status);

			assertEquals(200, status[0]);
			assertEquals(7, body.getJsonArray("daily").size(), "The window must be limited to 7 buckets");
			// The run outside the 7 day window must not appear in any bucket.
			long totalSuccess = body.getJsonArray("daily").stream()
				.mapToLong(b -> ((JsonObject) b).getLong("successCount"))
				.sum();
			assertEquals(4, totalSuccess);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An invalid days parameter is a 400")
	void testStatsInvalidDaysParameter() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, "/api/v1/pipelines/runs/stats?days=nope", client.getToken(), status);
			assertEquals(400, status[0], "A non-numeric days parameter must yield 400");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without READ_PIPELINE_RUN is 403")
	void testWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe holds only READ_USER - not READ_PIPELINE_RUN.
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, "/api/v1/pipelines/runs/stats", login.getToken(), status);
			assertEquals(403, status[0], "A caller lacking READ_PIPELINE_RUN must be forbidden");
		} finally {
			vertx.close();
		}
	}

	// ── HTTP helper ──────────────────────────────────────────────────────

	private JsonObject httpSend(Vertx vertx, HttpMethod method, String path, String token, int[] statusOut) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		client.request(method, restPort(), "localhost", path)
			.compose(req -> {
				if (token != null) {
					req.putHeader("Authorization", "Bearer " + token);
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
