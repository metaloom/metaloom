package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code POST /api/v1/pipelines/:uuid/run} and {@code DELETE /api/v1/pipelines/:uuid}.
 *
 * <p>
 * The run route has three outcomes and all three matter. A definition that cannot be parsed is a
 * <b>400</b>; a graph containing a kind no online worker will run is a <b>503</b>; anything else is
 * a <b>202</b> and a {@code SOURCE_TASK} on a worker's socket. The two refusals are asserted to
 * leave <em>no</em> {@code pipeline_run} row behind: a rejected run that still recorded a row would
 * show up in the run history as a run that never started and never finished.
 * </p>
 *
 * <p>
 * The 202 case is checked from the worker's side rather than from the response, because the response
 * only says a task was dispatched - it cannot say <em>what</em>. The payload is the contract between
 * Loom and Cortex: the run it belongs to, the source node and kind to run, and the options that node
 * runs with after the run request has been merged over the ones in the definition.
 * </p>
 */
public class PipelineRunDispatchEndpointTest extends AbstractEndpointTest {

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	/**
	 * Retains the per-connection clients so they are not garbage collected mid-test - a dropped
	 * client closes its socket, which would make a still-registered worker look disconnected.
	 */
	private final List<WebSocketClient> wsClients = new CopyOnWriteArrayList<>();

	// ── Fixtures ─────────────────────────────────────────────────────────

	/** A valid two-node graph whose source node carries options of its own. */
	private static JsonObject definition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true)
					.put("options", new JsonObject()
						.put("path", "/media/from-definition")
						.put("emitStates", new JsonArray().add("NEW"))))
				.add(new JsonObject().put("id", "pn2").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "media")));
	}

	private PipelineResponse createPipeline(LoomHttpClient client) throws LoomClientException {
		return client.createPipeline(new PipelineCreateRequest()
			.setName("dispatch-test-" + UUID.randomUUID())
			.setDefinition(definition())).sync().body();
	}

	/**
	 * A pipeline whose stored latest version cannot be parsed - an edge pointing at a node that is
	 * not in the graph.
	 *
	 * <p>
	 * Written straight through the DAOs on purpose. {@code PipelineAuthoringService} validates before
	 * it stores, so this shape cannot be produced over REST; it is what a definition written by an
	 * older Loom, or edited in the database, looks like. The run route must still refuse it rather
	 * than start a run that has nothing to execute.
	 * </p>
	 */
	private UUID createPipelineWithUnparseableDefinition() {
		JsonObject broken = definition();
		broken.getJsonArray("edges").add(new JsonObject().put("id", "pe2").put("source", "pn1")
			.put("sourcePort", "media").put("target", "nope").put("targetPort", "media"));

		Pipeline pipeline = daos().pipelineDao().createPipeline(adminUuid(), "broken-" + UUID.randomUUID());
		daos().pipelineDao().store(pipeline);
		PipelineVersion version = daos().pipelineVersionDao().createVersion(adminUuid(), pipeline.getUuid(), 1,
			"broken", null, broken, true, 0, false, null);
		daos().pipelineVersionDao().store(version);
		pipeline.setLatestVersionUuid(version.getUuid());
		daos().pipelineDao().update(pipeline);
		return pipeline.getUuid();
	}

	private String runPath(UUID pipelineUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/run";
	}

	// ── Dispatch ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("A run with no worker for the graph's kinds is a 503 and records no run")
	void testRunWithoutProcessorIsUnavailable() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID uuid = createPipeline(client).getUuid();

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, runPath(uuid), client.getToken(),
				new JsonObject(), status);

			assertEquals(503, status[0], "A graph no worker can run must be refused up front");
			assertThat(body.getBoolean("dispatched")).isFalse();
			assertThat(body.getString("message"))
				.as("the refusal must name the kinds nobody accepts, or it is not actionable")
				.contains("filesystem-source");
			assertThat(daos().pipelineRunDao().loadByPipeline(uuid))
				.as("a refused run must not leave a row in the run history")
				.isEmpty();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A run over an unparseable definition is a 400 and records no run")
	void testRunWithInvalidGraphIsBadRequest() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID uuid = createPipelineWithUnparseableDefinition();

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, runPath(uuid), client.getToken(),
				new JsonObject(), status);

			// The graph is parsed before a worker is looked for, so this is a 400 whether or not
			// any worker is online - a definition that cannot run as drawn is the caller's problem.
			assertEquals(400, status[0], "A definition that cannot be parsed must be rejected");
			assertThat(body.getString("message"))
				.as("the message must name the dangling reference")
				.contains("nope");
			assertThat(daos().pipelineRunDao().loadByPipeline(uuid))
				.as("a rejected definition must not leave a row in the run history")
				.isEmpty();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A run with a registered worker is a 202 and hands that worker the source task")
	void testRunDispatchesSourceTask() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID uuid = createPipeline(client).getUuid();

			List<JsonObject> frames = new CopyOnWriteArrayList<>();
			WebSocket ws = connectProcessorWs(vertx, frames);
			ws.writeTextMessage(registerMessage("dispatch-worker", "cortex-dispatch", "10.0.9.10:9090", 1, "CPU").encode());
			assertNotNull(awaitFrame(frames, "REGISTERED"), "The fake worker must be registered before the run");

			int[] status = new int[1];
			// A run request that overrides the root the definition scans, so the assertion below
			// distinguishes "the definition's options were forwarded" from "the request won".
			JsonObject response = httpSend(vertx, HttpMethod.POST, runPath(uuid), client.getToken(),
				new JsonObject().put("path", "/media/from-request"), status);

			assertEquals(202, status[0], "An accepted run is a 202");
			assertThat(response.getBoolean("dispatched")).isTrue();
			assertEquals("dispatch-worker", response.getString("processorNodeId"));
			UUID runUuid = UUID.fromString(response.getString("runUuid"));

			JsonObject sourceTask = awaitFrame(frames, "SOURCE_TASK");
			assertNotNull(sourceTask, "The chosen worker must actually receive the source task");
			JsonObject task = sourceTask.getJsonObject("body");
			assertEquals(runUuid.toString(), task.getString("runUuid"),
				"The task must name the run the response handed back, or results cannot be attributed");
			assertEquals("pn1", task.getString("nodeId"));
			assertEquals("filesystem-source", task.getString("nodeKind"), "Only the source node is dispatched");

			JsonObject options = task.getJsonObject("options");
			assertEquals("/media/from-request", options.getString("path"),
				"The run request's selection overrides the definition's");
			assertThat(options.getJsonArray("emitStates"))
				.as("options the request said nothing about survive from the definition")
				.containsExactly("NEW");

			// The run is recorded now, unlike in the two refusal cases above.
			List<PipelineRun> runs = daos().pipelineRunDao().loadByPipeline(uuid);
			assertThat(runs).hasSize(1);
			assertEquals(runUuid, runs.get(0).getUuid());
			assertEquals("RUNNING", runs.get(0).getStatus());

			ws.close();
		} finally {
			vertx.close();
		}
	}

	// ── Delete ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("Deleting a pipeline removes its versions and its runs, and nothing else")
	void testDeleteRemovesVersionsAndRuns() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			PipelineResponse doomed = createPipeline(client);
			UUID uuid = doomed.getUuid();
			client.updatePipeline(uuid, new PipelineUpdateRequest().setDescription("second version")).sync().body();
			PipelineRun run = createRun(uuid);
			PipelineRunItem item = createItem(run.getUuid());

			// A second pipeline with an identical row set, so the assertions below tell a cascade
			// apart from a delete that simply emptied the tables.
			UUID survivor = createPipeline(client).getUuid();
			PipelineRun survivorRun = createRun(survivor);
			PipelineRunItem survivorItem = createItem(survivorRun.getUuid());

			assertThat(daos().pipelineVersionDao().loadByPipeline(uuid)).hasSize(2);
			client.deletePipeline(uuid).sync();

			assertNull(daos().pipelineDao().load(uuid), "The pipeline itself is gone");
			assertThat(daos().pipelineVersionDao().loadByPipeline(uuid))
				.as("every version of the deleted pipeline goes with it")
				.isEmpty();
			assertThat(daos().pipelineRunDao().loadByPipeline(uuid))
				.as("its runs cascade, or the run history would reference a pipeline that is gone")
				.isEmpty();
			assertNull(daos().pipelineRunItemDao().load(item.getUuid()),
				"The items of a cascaded run cascade with it");

			assertNotNull(daos().pipelineDao().load(survivor), "An unrelated pipeline must survive");
			assertThat(daos().pipelineVersionDao().loadByPipeline(survivor)).hasSize(1);
			assertThat(daos().pipelineRunDao().loadByPipeline(survivor)).hasSize(1);
			assertNotNull(daos().pipelineRunItemDao().load(survivorItem.getUuid()));
		}
	}

	@Test
	@DisplayName("Deleting a pipeline needs DELETE_PIPELINE")
	void testDeleteNeedsDeletePipeline() throws Exception {
		UUID uuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			uuid = createPipeline(admin).getUuid();
		}

		try (LoomHttpClient denied = loginPermissionlessClient()) {
			expect(403, "Forbidden", denied.deletePipeline(uuid));
		}
		assertNotNull(daos().pipelineDao().load(uuid), "A forbidden delete must leave the pipeline alone");
		assertThat(daos().pipelineVersionDao().loadByPipeline(uuid))
			.as("nor may it take the versions")
			.hasSize(1);

		try (LoomHttpClient granted = loginClientWith("pipeline-deleter", Permission.DELETE_PIPELINE)) {
			granted.deletePipeline(uuid).sync();
		}
		assertNull(daos().pipelineDao().load(uuid));
	}

	// ── DAO fixtures ─────────────────────────────────────────────────────

	private PipelineRun createRun(UUID pipelineUuid) {
		PipelineRun run = daos().pipelineRunDao().createPipelineRun(adminUuid(), pipelineUuid, 1);
		run.setStatus("SUCCESS");
		daos().pipelineRunDao().store(run);
		return run;
	}

	private PipelineRunItem createItem(UUID runUuid) {
		PipelineRunItem item = daos().pipelineRunItemDao().createRunItem(adminUuid(), runUuid, 0, "/media/a.mp4");
		item.setState("SUCCESS");
		daos().pipelineRunItemDao().store(item);
		return item;
	}

	// ── Fake processor ───────────────────────────────────────────────────

	/**
	 * Connect a worker socket that records every frame the server sends it.
	 *
	 * <p>
	 * A recording handler rather than the request/response helper in {@code ProcessorEndpointTest}:
	 * the source task arrives unsolicited, long after the registration it answers, so a handler that
	 * completes one future would have been replaced by then and the frame would be dropped.
	 * </p>
	 */
	private WebSocket connectProcessorWs(Vertx vertx, List<JsonObject> frames) throws Exception {
		WebSocketClient wsClient = vertx.createWebSocketClient();
		wsClients.add(wsClient);
		CompletableFuture<WebSocket> future = new CompletableFuture<>();
		wsClient.connect(new WebSocketConnectOptions()
			.setHost("localhost")
			.setPort(restPort())
			.setURI("/api/v1/processors/ws"))
			.onSuccess(future::complete)
			.onFailure(future::completeExceptionally);
		WebSocket ws = future.get(10, TimeUnit.SECONDS);
		ws.textMessageHandler(text -> frames.add(new JsonObject(text)));
		return ws;
	}

	private static JsonObject registerMessage(String nodeId, String name, String host, int priority, String... capabilities) {
		JsonArray caps = new JsonArray();
		for (String capability : capabilities) {
			caps.add(capability);
		}
		return new JsonObject()
			.put("type", "REGISTER")
			.put("body", new JsonObject()
				.put("nodeId", nodeId)
				.put("name", name)
				.put("host", host)
				.put("priority", priority)
				.put("capabilities", caps));
	}

	/** The first frame of the given type, or null if none arrived within ten seconds. */
	private static JsonObject awaitFrame(List<JsonObject> frames, String type) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			for (JsonObject frame : frames) {
				if (type.equals(frame.getString("type"))) {
					return frame;
				}
			}
			Thread.sleep(50);
		}
		return null;
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
