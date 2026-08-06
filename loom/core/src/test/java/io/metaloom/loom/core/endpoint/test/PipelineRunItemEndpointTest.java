package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.pipeline.RunItemState;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemRecord;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code GET /api/v1/pipelines/:uuid/runs/:runUuid/items}.
 *
 * <p>The items of a run are queryable, paged and filterable by state. Requesting the
 * items of an unknown run - or a run that belongs to a different pipeline - is a 404.
 * A caller lacking {@code READ_PIPELINE_RUN} is a 403.</p>
 */
public class PipelineRunItemEndpointTest {

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

	private PipelineRunItemDao runItemDao() {
		return loom.internal().daos().pipelineRunItemDao();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	/** A pipeline plus a run, created directly through the DAOs. */
	private PipelineRun createRun() {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		Pipeline pipeline = pipelineDao().createPipeline(adminUuid, "run-item-test-" + UUID.randomUUID());
		pipelineDao().store(pipeline);
		PipelineRun run = runDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		runDao().store(run);
		return run;
	}

	/** Persist one item in a run with the given sequence, state and optional error message. */
	private PipelineRunItem addItem(UUID runUuid, long seq, RunItemState state, String errorMessage) {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		PipelineRunItem item = runItemDao().createRunItem(adminUuid, runUuid, seq, "/media/file-" + seq + ".mp4");
		item.setState(state);
		item.setErrorMessage(errorMessage);
		runItemDao().store(item);
		return item;
	}

	private String itemsPath(UUID pipelineUuid, UUID runUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/items";
	}

	// ── Tests ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("Items of a run are returned")
	void testListItems() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();
			addItem(run.getUuid(), 0, RunItemState.SUCCESS, null);
			addItem(run.getUuid(), 1, RunItemState.FAILED, "boom");
			addItem(run.getUuid(), 2, RunItemState.PENDING, null);

			PipelineRunItemListResponse response = client.listPipelineRunItems(run.getPipelineUuid(), run.getUuid()).sync().body();
			assertThat(response.getData()).hasSize(3);
			assertThat(response.getData()).extracting(PipelineRunItemRecord::getState)
				.containsExactlyInAnyOrder(RunItemState.SUCCESS, RunItemState.FAILED, RunItemState.PENDING);
			PipelineRunItemRecord failed = response.getData().stream().filter(i -> i.getState() == RunItemState.FAILED).findFirst().orElseThrow();
			assertThat(failed.getErrorMessage()).isEqualTo("boom");
			assertThat(failed.getMediaPath()).isEqualTo("/media/file-1.mp4");
			assertThat(failed.getRunUuid()).isEqualTo(run.getUuid());
		}
	}

	@Test
	@DisplayName("A run with no items yields an empty list")
	void testListItemsEmpty() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();

			PipelineRunItemListResponse response = client.listPipelineRunItems(run.getPipelineUuid(), run.getUuid()).sync().body();
			assertThat(response.getData()).isNullOrEmpty();
		}
	}

	@Test
	@DisplayName("The item list is paged to the default page size")
	void testListItemsPaged() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();
			for (int i = 0; i < 30; i++) {
				addItem(run.getUuid(), i, RunItemState.SUCCESS, null);
			}

			PipelineRunItemListResponse response = client.listPipelineRunItems(run.getPipelineUuid(), run.getUuid()).sync().body();
			// 30 items were created but the default page holds only 25 - proving the endpoint pages.
			assertThat(response.getData()).as("the first page holds the default 25 items").hasSize(25);
			assertThat(response.getMetainfo().getPerPage()).isEqualTo(25L);
		}
	}

	@Test
	@DisplayName("The item list can be filtered by state")
	void testListItemsFilteredByState() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();
			addItem(run.getUuid(), 0, RunItemState.SUCCESS, null);
			addItem(run.getUuid(), 1, RunItemState.FAILED, "boom");
			addItem(run.getUuid(), 2, RunItemState.FAILED, "kaboom");
			addItem(run.getUuid(), 3, RunItemState.PENDING, null);

			PipelineRunItemListResponse response = client.listPipelineRunItems(run.getPipelineUuid(), run.getUuid())
				.addEquals(LoomFilterKey.STATUS, "FAILED")
				.sync().body();
			assertThat(response.getData()).hasSize(2);
			assertThat(response.getData()).allMatch(i -> i.getState() == RunItemState.FAILED);
		}
	}

	@Test
	@DisplayName("Items of an unknown run are a 404")
	void testUnknownRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, itemsPath(UUID.randomUUID(), UUID.randomUUID()), client.getToken(), status);

			assertEquals(404, status[0], "An unknown run must yield 404");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Items requested via the wrong pipeline are a 404")
	void testRunOfOtherPipelineIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();
			PipelineRun other = createRun();

			int[] status = new int[1];
			// The run exists, but not under `other`'s pipeline.
			httpSend(vertx, HttpMethod.GET, itemsPath(other.getPipelineUuid(), run.getUuid()), client.getToken(), status);

			assertEquals(404, status[0], "A run that does not belong to the pipeline must yield 404");
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
			PipelineRun run = createRun();
			addItem(run.getUuid(), 0, RunItemState.SUCCESS, null);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, itemsPath(run.getPipelineUuid(), run.getUuid()), login.getToken(), status);

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
