package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
import io.metaloom.loom.pipeline.engine.NodeDispatcher;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code POST /api/v1/pipelines/:uuid/runs/:runUuid/nodes/:nodeId/reexecutions}.
 *
 * <p>
 * The route exists so an operator who stopped a run at a node can change a setting and see the same
 * input answered differently. Two things therefore have to be true at once, and the tests are
 * organised around them: the node must genuinely <strong>run again</strong> — asserted against what
 * reached the dispatcher, not against a status code — and the pipeline definition must be
 * <strong>left alone</strong>, so experimenting on a live run can never change what everyone else
 * runs.
 * </p>
 *
 * <p>
 * The rejections matter as much as the success. Options are checked against the node's declared
 * parameters here and nowhere else in the request path, so a value the node would choke on is a 400
 * with a message rather than a worker failure discovered later in a log.
 * </p>
 */
public class PipelineNodeReExecuteEndpointTest {

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

	/** A pipeline plus a run row, created directly through the DAOs. */
	private PipelineRun createRun() {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		Pipeline pipeline = loom.internal().daos().pipelineDao().createPipeline(adminUuid, "rx-test-" + UUID.randomUUID());
		loom.internal().daos().pipelineDao().store(pipeline);
		PipelineRun run = runDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		run.setStatus("RUNNING");
		runDao().store(run);
		return run;
	}

	/**
	 * src -> hash -> thumb.
	 *
	 * <p>
	 * The breakpoint sits on {@code thumb} rather than {@code hash} because the thumbnail node
	 * declares bounded integer parameters, which is what lets the validation cases below be about a
	 * real node's real contract instead of an invented one.
	 * </p>
	 */
	private PipelineGraph linearGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")
					.put("options", new JsonObject().put("cols", 6).put("rows", 1))))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media"))
				.add(new JsonObject().put("source", "hash").put("sourcePort", "hash").put("target", "thumb").put("targetPort", "media")));
		return new PipelineGraphParser().parse("rx", definition, true, false, 0);
	}

	/** Accepts everything and remembers the tasks, standing in for a worker fleet. */
	private static class RecordingDispatcher implements NodeDispatcher {

		final List<NodeTask> tasks = new CopyOnWriteArrayList<>();

		@Override
		public String dispatch(NodeTask task) {
			tasks.add(task);
			return "test-worker";
		}

		List<NodeTask> tasksFor(String nodeId) {
			return tasks.stream().filter(t -> t.getNodeId().equals(nodeId)).toList();
		}

		/** The most recent attempt at a node — which, after a re-execution, is not the first. */
		NodeTask latestTaskFor(String nodeId) {
			List<NodeTask> attempts = tasksFor(nodeId);
			if (attempts.isEmpty()) {
				throw new AssertionError("Nothing was dispatched for node " + nodeId);
			}
			return attempts.get(attempts.size() - 1);
		}
	}

	private record LiveRun(PipelineRun run, PipelineRunEngine engine, RecordingDispatcher dispatcher, String itemId) {
	}

	/** A registered run stopped at {@code thumb}, with that node's first result already in. */
	private LiveRun startRunHeldAtThumb() {
		PipelineRun run = createRun();
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, run.getUuid());
		loom.internal().pipelineRunRegistry().register(run.getUuid(), engine);
		engine.setBreakpoints(List.of("thumb"));
		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		LiveRun live = new LiveRun(run, engine, dispatcher, itemId);
		settle(live, "hash", "abc");
		settle(live, "thumb", "/tmp/sheet.jpg");
		return live;
	}

	/** Settle a node the way a worker would. */
	private void settle(LiveRun live, String nodeId, Object value) {
		NodeTask task = live.dispatcher().latestTaskFor(nodeId);
		Map<String, PortPayload> outputs = Map.of(nodeId,
			PortPayload.one("hash/sha512", Origin.single(live.itemId()), value));
		live.engine().onNodeTaskResult(live.itemId(),
			NodeTaskResult.completed(task.getTaskUuid(), nodeId, 5, outputs));
	}

	private String reExecutePath(UUID pipelineUuid, UUID runUuid, String nodeId) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/nodes/" + nodeId + "/reexecutions";
	}

	private JsonObject body(String itemId, JsonObject options) {
		JsonObject request = new JsonObject().put("itemUuid", itemId).put("elementSeq", 0);
		return options == null ? request : request.put("options", options);
	}

	// ── Running the node again ───────────────────────────────────────────

	@Test
	@DisplayName("Re-executing a held node runs it again with the new settings")
	void testReExecuteRunsTheNodeAgain() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startRunHeldAtThumb();
			assertEquals(1, live.dispatcher().tasksFor("thumb").size());

			int[] status = new int[1];
			JsonObject response = httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), client.getToken(),
				body(live.itemId(), new JsonObject().put("cols", 4)), status);

			assertEquals(200, status[0]);
			assertEquals(1, response.getInteger("generation"), "The first re-execution is generation 1");

			List<NodeTask> attempts = live.dispatcher().tasksFor("thumb");
			assertThat(attempts).as("the node must actually be dispatched again").hasSize(2);
			assertEquals(4, attempts.get(1).getOptions().get("cols"), "The changed setting must reach the worker");
			assertEquals(1, attempts.get(1).getOptions().get("rows"),
				"Changing one setting must not drop the others");
			assertEquals(1, attempts.get(1).getGeneration());
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Re-executing creates no new pipeline version")
	void testReExecuteDoesNotTouchTheDefinition() throws Exception {
		// The property that makes experimenting on a live run safe. An override is run state; keeping
		// a setting is a separate, deliberate act through the pipeline update endpoint.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startRunHeldAtThumb();
			UUID versionBefore = loom.internal().daos().pipelineDao()
				.load(live.run().getPipelineUuid()).getLatestVersionUuid();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), client.getToken(),
				body(live.itemId(), new JsonObject().put("cols", 4)), status);
			assertEquals(200, status[0]);

			assertThat(loom.internal().daos().pipelineDao().load(live.run().getPipelineUuid()).getLatestVersionUuid())
				.as("a re-execution must not create a pipeline version")
				.isEqualTo(versionBefore);
			assertEquals(6, live.engine().getGraph().getNode("thumb").getOptions().get("cols"),
				"the run's own graph must still report what the pipeline says");
		} finally {
			vertx.close();
		}
	}

	// ── Rejections ───────────────────────────────────────────────────────

	@Test
	@DisplayName("A parameter outside its declared range is a 400 that names it")
	void testOutOfRangeOptionIsBadRequest() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startRunHeldAtThumb();

			int[] status = new int[1];
			JsonObject response = httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), client.getToken(),
				body(live.itemId(), new JsonObject().put("cols", 99)), status);

			assertEquals(400, status[0]);
			assertThat(response.getString("message")).contains("cols");
			assertThat(live.dispatcher().tasksFor("thumb"))
				.as("a rejected request must not dispatch anything").hasSize(1);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A parameter the node does not declare is a 400")
	void testUnknownOptionIsBadRequest() throws Exception {
		// Without this, a mistyped key would be accepted, silently ignored by the node, and the
		// operator would conclude the setting does nothing.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startRunHeldAtThumb();

			int[] status = new int[1];
			JsonObject response = httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), client.getToken(),
				body(live.itemId(), new JsonObject().put("colsss", 4)), status);

			assertEquals(400, status[0]);
			assertThat(response.getString("message")).contains("colsss");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An unknown node id, or a missing itemUuid, is a 400")
	void testUnknownNodeOrMissingItemIsBadRequest() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startRunHeldAtThumb();

			int[] status = new int[1];
			JsonObject response = httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thmub"), client.getToken(),
				body(live.itemId(), null), status);
			assertEquals(400, status[0]);
			assertThat(response.getString("message")).as("the message must name the offending id").contains("thmub");

			httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), client.getToken(),
				new JsonObject().put("elementSeq", 0), status);
			assertEquals(400, status[0], "a re-execution runs one node over one item and must say which");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Re-executing an execution that is not held is a 409")
	void testNotHeldIsConflict() throws Exception {
		// A conflict rather than a bad request: the call is well-formed and would have been accepted
		// a moment earlier. Only a held execution may be re-run, because a hold is what guarantees
		// nothing downstream has already consumed the result being discarded.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startRunHeldAtThumb();
			live.engine().releaseNode("thumb");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), client.getToken(),
				body(live.itemId(), null), status);

			assertEquals(409, status[0]);
			assertThat(live.dispatcher().tasksFor("thumb")).hasSize(1);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Re-executing on a run with no live engine is a 409")
	void testWithoutLiveEngineIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, reExecutePath(run.getPipelineUuid(), run.getUuid(), "thumb"),
				client.getToken(), body(UUID.randomUUID().toString(), null), status);

			assertEquals(409, status[0], "a run lost to a restart cannot be debugged");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Re-executing on an unknown run is a 404")
	void testUnknownRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, reExecutePath(UUID.randomUUID(), UUID.randomUUID(), "thumb"),
				client.getToken(), body(UUID.randomUUID().toString(), null), status);

			assertEquals(404, status[0]);
		} finally {
			vertx.close();
		}
	}

	// ── Permissions ──────────────────────────────────────────────────────

	@Test
	@DisplayName("A caller without UPDATE_PIPELINE_RUN cannot re-execute")
	void testWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe holds only READ_USER.
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			LiveRun live = startRunHeldAtThumb();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), login.getToken(),
				body(live.itemId(), new JsonObject().put("cols", 4)), status);

			assertEquals(403, status[0]);
			assertThat(live.dispatcher().tasksFor("thumb"))
				.as("a forbidden request must not run the node").hasSize(1);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Re-executing anonymously is refused")
	void testAnonymouslyIsRefused() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startRunHeldAtThumb();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				reExecutePath(live.run().getPipelineUuid(), live.run().getUuid(), "thumb"), null,
				body(live.itemId(), null), status);

			assertThat(status[0]).as("an unauthenticated re-execution must not be served").isIn(401, 403);
		} finally {
			vertx.close();
		}
	}

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
