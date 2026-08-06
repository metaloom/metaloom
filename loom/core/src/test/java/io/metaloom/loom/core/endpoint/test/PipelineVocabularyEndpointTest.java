package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.api.pipeline.RunItemState;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Every value of the three pipeline vocabularies survives the round trip out over REST.
 *
 * <p>
 * The typed model is only worth anything if the wire form stays the documented token, so each value
 * is checked twice: once through the generated client, which deserialises it back into the enum,
 * and once against the raw JSON, which is what a non-Java caller and the UI actually see.
 * </p>
 */
public class PipelineVocabularyEndpointTest {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	// ── Round trip ───────────────────────────────────────────────────────

	@Test
	@DisplayName("Every run status is served as itself, and as its own name in the JSON")
	void testRunStatusRoundTrip() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Pipeline pipeline = createPipeline();
			for (PipelineRunStatus status : PipelineRunStatus.values()) {
				PipelineRun run = addRun(pipeline, status);

				PipelineRunListResponse response = client.listPipelineRuns(pipeline.getUuid()).sync().body();
				PipelineRunRecord record = response.getData().stream()
					.filter(r -> run.getUuid().equals(r.getUuid())).findFirst().orElseThrow();
				assertThat(record.getStatus()).as("status of run %s", run.getUuid()).isEqualTo(status);

				JsonObject json = jsonFor(vertx, runsPath(pipeline.getUuid()), client.getToken(), run.getUuid());
				assertEquals(status.name(), json.getString("status"),
					"the wire form must stay the documented token");
			}
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Every run item state is served as itself, and as its own name in the JSON")
	void testRunItemStateRoundTrip() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Pipeline pipeline = createPipeline();
			PipelineRun run = addRun(pipeline, PipelineRunStatus.RUNNING);

			long seq = 0;
			for (RunItemState state : RunItemState.values()) {
				PipelineRunItem item = addItem(run, seq++, state);

				PipelineRunItemListResponse response = client.listPipelineRunItems(pipeline.getUuid(), run.getUuid())
					.sync().body();
				PipelineRunItemRecord record = response.getData().stream()
					.filter(i -> item.getUuid().equals(i.getUuid())).findFirst().orElseThrow();
				assertThat(record.getState()).as("state of item %s", item.getUuid()).isEqualTo(state);

				JsonObject json = jsonFor(vertx, itemsPath(pipeline.getUuid(), run.getUuid()), client.getToken(),
					item.getUuid());
				assertEquals(state.name(), json.getString("state"),
					"the wire form must stay the documented token");
			}
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Every node task state is served as itself, and as its own name in the JSON")
	void testNodeTaskStateRoundTrip() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Pipeline pipeline = createPipeline();
			PipelineRun run = addRun(pipeline, PipelineRunStatus.RUNNING);
			PipelineRunItem item = addItem(run, 0, RunItemState.RUNNING);

			int elementSeq = 0;
			for (NodeTaskState state : NodeTaskState.values()) {
				PipelineNodeTask task = addTask(item, elementSeq++, state);

				PipelineNodeTaskListResponse response = client
					.listPipelineRunItemTasks(pipeline.getUuid(), run.getUuid(), item.getUuid()).sync().body();
				PipelineNodeTaskRecord record = response.getData().stream()
					.filter(t -> task.getUuid().equals(t.getUuid())).findFirst().orElseThrow();
				assertThat(record.getState()).as("state of task %s", task.getUuid()).isEqualTo(state);

				JsonObject json = jsonFor(vertx, tasksPath(pipeline.getUuid(), run.getUuid(), item.getUuid()),
					client.getToken(), task.getUuid());
				assertEquals(state.name(), json.getString("state"),
					"the wire form must stay the documented token");
			}
		} finally {
			vertx.close();
		}
	}

	// ── Rejection ────────────────────────────────────────────────────────

	@Test
	@DisplayName("Filtering items on a state outside the vocabulary is a 400 naming the value")
	void testUnknownStateFilterIsBadRequest() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Pipeline pipeline = createPipeline();
			PipelineRun run = addRun(pipeline, PipelineRunStatus.RUNNING);
			addItem(run, 0, RunItemState.SUCCESS);

			int[] status = new int[1];
			// FAILURE is the engine's spelling, not the column's. A caller who guesses it must be
			// told so - answering with an empty page would read as "no failed items".
			String filter = URLEncoder.encode(LoomFilterKey.STATUS.eq("FAILURE").toString(), StandardCharsets.UTF_8);
			JsonObject body = httpSend(vertx, HttpMethod.GET,
				itemsPath(pipeline.getUuid(), run.getUuid()) + "?filter=" + filter, client.getToken(), status);

			assertEquals(400, status[0], "an unknown state must be a bad request, not an empty result");
			assertThat(body.getString("message")).contains("pipeline_run_item.state").contains("FAILURE");

			// The valid spelling of the same intent still works, so this is a rejection of the
			// value and not of filtering by state.
			assertThat(client.listPipelineRunItems(pipeline.getUuid(), run.getUuid())
				.addEquals(LoomFilterKey.STATUS, RunItemState.SUCCESS.name()).sync().body().getData()).hasSize(1);
		} finally {
			vertx.close();
		}
	}

	// ── Fixtures ─────────────────────────────────────────────────────────

	private UUID adminUuid() {
		return loom.internal().daos().userDao().loadAdmin().getUuid();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	private Pipeline createPipeline() {
		Pipeline pipeline = loom.internal().daos().pipelineDao()
			.createPipeline(adminUuid(), "vocabulary-test-" + UUID.randomUUID());
		loom.internal().daos().pipelineDao().store(pipeline);
		return pipeline;
	}

	private PipelineRun addRun(Pipeline pipeline, PipelineRunStatus status) {
		PipelineRun run = loom.internal().daos().pipelineRunDao()
			.createPipelineRun(adminUuid(), pipeline.getUuid(), 1);
		run.setStatus(status);
		loom.internal().daos().pipelineRunDao().store(run);
		return run;
	}

	private PipelineRunItem addItem(PipelineRun run, long seq, RunItemState state) {
		PipelineRunItem item = loom.internal().daos().pipelineRunItemDao()
			.createRunItem(adminUuid(), run.getUuid(), seq, "/media/file-" + seq + ".mp4");
		item.setState(state);
		loom.internal().daos().pipelineRunItemDao().store(item);
		return item;
	}

	private PipelineNodeTask addTask(PipelineRunItem item, int elementSeq, NodeTaskState state) {
		PipelineNodeTask task = loom.internal().daos().pipelineNodeTaskDao()
			.createNodeTask(adminUuid(), item.getUuid(), item.getRunUuid(), "sha512", "hash-sha512");
		task.setElementSeq(elementSeq);
		task.setState(state);
		loom.internal().daos().pipelineNodeTaskDao().store(task);
		return task;
	}

	// ── Paths ────────────────────────────────────────────────────────────

	private String runsPath(UUID pipelineUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs";
	}

	private String itemsPath(UUID pipelineUuid, UUID runUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/items";
	}

	private String tasksPath(UUID pipelineUuid, UUID runUuid, UUID itemUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/items/" + itemUuid + "/tasks";
	}

	// ── HTTP helpers ─────────────────────────────────────────────────────

	/**
	 * The raw JSON object for one uuid in a list response — the wire form, before any Java model
	 * has had a chance to normalise it.
	 */
	private JsonObject jsonFor(Vertx vertx, String path, String token, UUID uuid) throws Exception {
		int[] status = new int[1];
		JsonObject response = httpSend(vertx, HttpMethod.GET, path + "?perPage=100", token, status);
		assertEquals(200, status[0], "listing " + path + " must succeed");
		JsonArray data = response.getJsonArray("data");
		for (int i = 0; i < data.size(); i++) {
			JsonObject entry = data.getJsonObject(i);
			if (uuid.toString().equals(entry.getString("uuid"))) {
				return entry;
			}
		}
		throw new AssertionError("No entry with uuid " + uuid + " in " + path);
	}

	private JsonObject httpSend(Vertx vertx, HttpMethod method, String path, String token, int[] statusOut)
		throws Exception {
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
			.onSuccess(buffer -> future.complete(buffer.length() == 0 ? new JsonObject() : buffer.toJsonObject()))
			.onFailure(future::completeExceptionally);

		return future.get(30, TimeUnit.SECONDS);
	}

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}
}
