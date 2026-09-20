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
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code GET /api/v1/pipeline-runs/:runUuid} - resolving a run without knowing its pipeline.
 *
 * <p>
 * Every other run route is nested under {@code /pipelines/:uuid/}, which is unusable for a caller holding only a run uuid. A
 * {@code PIPELINE_RUN_FAILED} notification is exactly that case: {@code notification.pipeline_run_uuid} is the only subject it carries, so clicking
 * one could not open the run that failed. What this route must guarantee is that the response carries {@code pipelineUuid}, because that is the
 * whole point - it is the way in to the nested routes.
 * </p>
 */
public class PipelineRunResolveEndpointTest {

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

	private PipelineRun createRun() {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		Pipeline pipeline = pipelineDao().createPipeline(adminUuid, "run-resolve-test-" + UUID.randomUUID());
		pipelineDao().store(pipeline);
		PipelineRun run = runDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		runDao().store(run);
		return run;
	}

	private String resolvePath(UUID runUuid) {
		return "/api/v1/pipeline-runs/" + runUuid;
	}

	// ── Tests ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("A run resolves by its own uuid and reports which pipeline it belongs to")
	void testResolveByRunUuid() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();

			PipelineRunRecord record = client.loadPipelineRun(run.getUuid()).sync().body();

			assertThat(record.getUuid()).isEqualTo(run.getUuid());
			// The load-bearing assertion: without this field the caller cannot reach any of the
			// pipeline-scoped run routes, which is the only reason this route exists.
			assertThat(record.getPipelineUuid()).as("the resolver must report the owning pipeline")
				.isEqualTo(run.getPipelineUuid());
		}
	}

	@Test
	@DisplayName("The resolver and the pipeline-scoped route agree")
	void testAgreesWithTheNestedRoute() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();

			PipelineRunRecord viaResolver = client.loadPipelineRun(run.getUuid()).sync().body();
			PipelineRunRecord viaPipeline = client.loadPipelineRun(run.getPipelineUuid(), run.getUuid()).sync().body();

			assertThat(viaResolver.getUuid()).isEqualTo(viaPipeline.getUuid());
			assertThat(viaResolver.getPipelineUuid()).isEqualTo(viaPipeline.getPipelineUuid());
			assertThat(viaResolver.getStatus()).isEqualTo(viaPipeline.getStatus());
		}
	}

	@Test
	@DisplayName("An unknown run uuid is 404, not an empty record")
	void testUnknownRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse login = client.login("admin", "finger").sync().body();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, resolvePath(UUID.randomUUID()), login.getToken(), status);

			assertEquals(404, status[0], "an unknown run must be a 404");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without READ_PIPELINE_RUN is 403")
	void testWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe holds only READ_USER - not READ_PIPELINE_RUN. Dropping the pipeline from the
			// path must not drop the permission check with it.
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			PipelineRun run = createRun();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, resolvePath(run.getUuid()), login.getToken(), status);

			assertEquals(403, status[0], "A caller lacking READ_PIPELINE_RUN must be forbidden");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An anonymous caller is rejected")
	void testAnonymousIsRejected() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, resolvePath(run.getUuid()), null, status);

			assertThat(status[0]).as("the route must be secured").isIn(401, 403);
		} finally {
			vertx.close();
		}
	}

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
