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
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskRecord;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies {@code GET /api/v1/pipelines/:uuid/runs/:runUuid/items/:itemUuid/tasks}.
 *
 * <p>This is the only route that exposes {@code pipeline_node_task}, and with it the outputs a
 * node actually emitted. Before it existed a partially-failed run could say <em>that</em> an item
 * failed but never which node failed or what it produced.</p>
 *
 * <p>The chain of ownership is what most of these tests are about: an item is addressed through
 * its run and the run through its pipeline, so none of the three may be substituted.</p>
 */
public class PipelineNodeTaskEndpointTest {

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

	private PipelineNodeTaskDao nodeTaskDao() {
		return loom.internal().daos().pipelineNodeTaskDao();
	}

	private UUID adminUuid() {
		return loom.internal().daos().userDao().loadAdmin().getUuid();
	}

	private void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	/** A pipeline, a run and one item, created directly through the DAOs. */
	private PipelineRunItem createItem() {
		Pipeline pipeline = pipelineDao().createPipeline(adminUuid(), "node-task-test-" + UUID.randomUUID());
		pipelineDao().store(pipeline);
		PipelineRun run = runDao().createPipelineRun(adminUuid(), pipeline.getUuid(), 1);
		runDao().store(run);
		PipelineRunItem item = runItemDao().createRunItem(adminUuid(), run.getUuid(), 0, "/media/example.mp4");
		item.setState("SUCCESS");
		runItemDao().store(item);
		return item;
	}

	private PipelineRun runOf(PipelineRunItem item) {
		return runDao().load(item.getRunUuid());
	}

	/** One settled node execution carrying a single-element payload on the given port. */
	private PipelineNodeTask addTask(PipelineRunItem item, String nodeId, String nodeKind, int elementSeq,
		String state, JsonObject outputs) {
		PipelineNodeTask task = nodeTaskDao().createNodeTask(adminUuid(), item.getUuid(), item.getRunUuid(), nodeId, nodeKind);
		task.setElementSeq(elementSeq);
		task.setState(state);
		task.setAttempt(1);
		task.setMaxAttempts(3);
		task.setDurationMs(42L);
		task.setOutputs(outputs);
		nodeTaskDao().store(task);
		return task;
	}

	private static JsonObject payload(String portId, String contentType, String cardinality, Object... values) {
		JsonArray elements = new JsonArray();
		for (int i = 0; i < values.length; i++) {
			elements.add(new JsonObject()
				.put("origin", new JsonObject().put("itemId", "item-1").put("seq", i).put("total", values.length))
				.put("value", values[i]));
		}
		return new JsonObject().put(portId, new JsonObject()
			.put("contentType", contentType)
			.put("cardinality", cardinality)
			.put("elements", elements));
	}

	private String tasksPath(UUID pipelineUuid, UUID runUuid, UUID itemUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/items/" + itemUuid + "/tasks";
	}

	// ── Tests ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("The node executions of an item are returned with their outputs intact")
	void testListTasks() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			addTask(item, "pn1", "filesystem-source", 0, "DONE",
				payload("media", "media/video", "ONE", "/media/example.mp4"));
			addTask(item, "pn2", "sha512", 0, "DONE",
				payload("sha512", "hash/sha512", "ONE", "0f8ef1c9"));

			PipelineNodeTaskListResponse response = client
				.listPipelineRunItemTasks(run.getPipelineUuid(), run.getUuid(), item.getUuid()).sync().body();

			assertThat(response.getData()).hasSize(2);
			assertThat(response.getData()).extracting(PipelineNodeTaskRecord::getNodeId)
				.containsExactlyInAnyOrder("pn1", "pn2");

			PipelineNodeTaskRecord hash = response.getData().stream()
				.filter(t -> "pn2".equals(t.getNodeId())).findFirst().orElseThrow();
			assertThat(hash.getNodeKind()).isEqualTo("sha512");
			assertThat(hash.getState()).isEqualTo("DONE");
			assertThat(hash.getAttempt()).isEqualTo(1);
			assertThat(hash.getMaxAttempts()).isEqualTo(3);
			assertThat(hash.getDurationMs()).isEqualTo(42L);
			assertThat(hash.getItemUuid()).isEqualTo(item.getUuid());
			assertThat(hash.getRunUuid()).isEqualTo(run.getUuid());

			// The whole point of the route: the payload survives the round trip keyed by output
			// port id, with its declared content type and cardinality alongside it. A client that
			// has never seen this node before can still render the result from that.
			JsonObject outputs = hash.getOutputs();
			assertThat(outputs).isNotNull();
			assertThat(outputs.getJsonObject("sha512").getString("contentType")).isEqualTo("hash/sha512");
			assertThat(outputs.getJsonObject("sha512").getString("cardinality")).isEqualTo("ONE");
			assertThat(outputs.getJsonObject("sha512").getJsonArray("elements").getJsonObject(0).getString("value"))
				.isEqualTo("0f8ef1c9");
		}
	}

	@Test
	@DisplayName("A fanned-out node yields one entry per element, distinguished by elementSeq")
	void testListTasksFanOut() throws Exception {
		// A node downstream of a MANY output runs once per element, and each of those runs is its
		// own row keyed by (item, node, elementSeq). Collapsing them would hide which element of a
		// fan-out failed, which is precisely what a fan-out makes hard to see.
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			addTask(item, "pn3", "facedescription", 0, "DONE", payload("embedding", "struct/embedding", "ONE", "[0.1]"));
			addTask(item, "pn3", "facedescription", 1, "DONE", payload("embedding", "struct/embedding", "ONE", "[0.2]"));
			addTask(item, "pn3", "facedescription", 2, "FAILED", null);

			PipelineNodeTaskListResponse response = client
				.listPipelineRunItemTasks(run.getPipelineUuid(), run.getUuid(), item.getUuid()).sync().body();

			assertThat(response.getData()).hasSize(3);
			assertThat(response.getData()).extracting(PipelineNodeTaskRecord::getElementSeq)
				.containsExactlyInAnyOrder(0, 1, 2);
			PipelineNodeTaskRecord failed = response.getData().stream()
				.filter(t -> "FAILED".equals(t.getState())).findFirst().orElseThrow();
			assertThat(failed.getElementSeq()).as("the failing element must be identifiable").isEqualTo(2);
		}
	}

	@Test
	@DisplayName("An item with no executions yields an empty list")
	void testListTasksEmpty() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);

			PipelineNodeTaskListResponse response = client
				.listPipelineRunItemTasks(run.getPipelineUuid(), run.getUuid(), item.getUuid()).sync().body();
			assertThat(response.getData()).isNullOrEmpty();
		}
	}

	@Test
	@DisplayName("An unknown item is a 404")
	void testUnknownItemIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, tasksPath(run.getPipelineUuid(), run.getUuid(), UUID.randomUUID()),
				client.getToken(), status);

			assertEquals(404, status[0], "An unknown item must yield 404");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An item addressed through a run it does not belong to is a 404")
	void testItemOfAnotherRunIsNotFound() throws Exception {
		// Without this check any item's outputs would be readable by naming any run the caller can
		// see, which quietly defeats the per-run ownership the route is addressed through.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRunItem other = createItem();
			PipelineRun otherRun = runOf(other);
			addTask(item, "pn1", "sha512", 0, "DONE", payload("sha512", "hash/sha512", "ONE", "secret"));

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, tasksPath(otherRun.getPipelineUuid(), otherRun.getUuid(), item.getUuid()),
				client.getToken(), status);

			assertEquals(404, status[0], "An item of a different run must not be readable");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A run addressed through the wrong pipeline is a 404")
	void testWrongPipelineIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, tasksPath(UUID.randomUUID(), run.getUuid(), item.getUuid()),
				client.getToken(), status);

			assertEquals(404, status[0], "A run addressed under the wrong pipeline must yield 404");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without READ_PIPELINE_RUN cannot read node executions")
	void testWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe holds only READ_USER — not READ_PIPELINE_RUN.
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			addTask(item, "pn1", "sha512", 0, "DONE", payload("sha512", "hash/sha512", "ONE", "secret"));

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, tasksPath(run.getPipelineUuid(), run.getUuid(), item.getUuid()),
				login.getToken(), status);

			assertEquals(403, status[0], "A caller lacking READ_PIPELINE_RUN must be forbidden");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An anonymous caller cannot read node executions")
	void testAnonymousIsUnauthorized() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, tasksPath(run.getPipelineUuid(), run.getUuid(), item.getUuid()),
				null, status);

			assertThat(status[0]).as("an unauthenticated read must not succeed").isNotEqualTo(200);
		} finally {
			vertx.close();
		}
	}

	// ── Previews ─────────────────────────────────────────────────────────

	/** A stored preview, in the shape NodePreviews.encode writes. */
	private static JsonObject storedPreview(String portId, byte[] data) {
		return new JsonObject().put(portId, new JsonObject()
			.put("mimeType", "image/jpeg")
			.put("width", 512)
			.put("height", 256)
			.put("data", java.util.Base64.getEncoder().encodeToString(data)));
	}

	@Test
	@DisplayName("Preview metadata is served with a fetch URL and without the bytes")
	void testPreviewMetadataCarriesUrlNotBytes() throws Exception {
		// Inlining base64 into the task list would mean the browser could not cache a thumbnail
		// per image and had to re-download every one of them with every refresh of the list.
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			PipelineNodeTask task = addTask(item, "pn2", "thumbnail", 0, "DONE",
				payload("thumbnail", "artifact/image", "ONE", "/var/cortex/thumb.jpg"));
			task.setPreviews(storedPreview("thumbnail", new byte[] { 1, 2, 3, 4 }));
			nodeTaskDao().update(task);

			PipelineNodeTaskListResponse response = client
				.listPipelineRunItemTasks(run.getPipelineUuid(), run.getUuid(), item.getUuid()).sync().body();

			JsonObject previews = response.getData().get(0).getPreviews();
			assertThat(previews).isNotNull();
			JsonObject entry = previews.getJsonObject("thumbnail");
			assertThat(entry.getString("data")).as("bytes must not be inlined").isNull();
			assertThat(entry.getString("mimeType")).isEqualTo("image/jpeg");
			assertThat(entry.getInteger("width")).isEqualTo(512);
			assertThat(entry.getString("url"))
				.contains("/pipelines/" + run.getPipelineUuid())
				.contains("/tasks/" + task.getUuid() + "/previews/thumbnail");
		}
	}

	@Test
	@DisplayName("The preview route serves the bytes, and revalidates with an ETag")
	void testPreviewBytesAreServed() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			byte[] bytes = new byte[] { 10, 20, 30, 40, 50 };
			PipelineNodeTask task = addTask(item, "pn2", "thumbnail", 0, "DONE",
				payload("thumbnail", "artifact/image", "ONE", "/var/cortex/thumb.jpg"));
			task.setPreviews(storedPreview("thumbnail", bytes));
			nodeTaskDao().update(task);

			String path = tasksPath(run.getPipelineUuid(), run.getUuid(), item.getUuid())
				+ "/" + task.getUuid() + "/previews/thumbnail";

			int[] status = new int[1];
			String[] etag = new String[1];
			byte[] body = httpGetBytes(vertx, path, client.getToken(), status, etag);

			assertEquals(200, status[0]);
			assertThat(body).isEqualTo(bytes);
			assertThat(etag[0]).as("an ETag makes the bytes cacheable").isNotBlank();

			// A conditional re-fetch is answered 304 rather than resending the image.
			int[] second = new int[1];
			byte[] notModified = httpGetBytes(vertx, path, client.getToken(), second, new String[1], etag[0]);
			assertEquals(304, second[0]);
			assertThat(notModified).isEmpty();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A skipped preview carries its reason and serves no bytes")
	void testSkippedPreviewHasReasonAndNoBytes() throws Exception {
		// "Too large to preview" and "this port emitted nothing" mean opposite things, so the
		// reason is kept and the byte route still 404s.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			PipelineNodeTask task = addTask(item, "pn2", "thumbnail", 0, "DONE",
				payload("thumbnail", "artifact/image", "ONE", "/var/cortex/huge.jpg"));
			task.setPreviews(new JsonObject().put("thumbnail",
				new JsonObject().put("skippedReason", "Preview exceeds 98304 bytes")));
			nodeTaskDao().update(task);

			PipelineNodeTaskListResponse response = client
				.listPipelineRunItemTasks(run.getPipelineUuid(), run.getUuid(), item.getUuid()).sync().body();
			JsonObject entry = response.getData().get(0).getPreviews().getJsonObject("thumbnail");
			assertThat(entry.getString("skippedReason")).contains("exceeds");
			assertThat(entry.getString("url")).as("nothing to fetch").isNull();

			int[] status = new int[1];
			httpGetBytes(vertx, tasksPath(run.getPipelineUuid(), run.getUuid(), item.getUuid())
				+ "/" + task.getUuid() + "/previews/thumbnail", client.getToken(), status, new String[1]);
			assertEquals(404, status[0]);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A task with no previews reports none")
	void testNoPreviewsWhenNotRequested() throws Exception {
		// The overwhelmingly common case: a run started without debug writes no previews at all.
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			addTask(item, "pn2", "sha512", 0, "DONE", payload("sha512", "hash/sha512", "ONE", "0f8e"));

			PipelineNodeTaskListResponse response = client
				.listPipelineRunItemTasks(run.getPipelineUuid(), run.getUuid(), item.getUuid()).sync().body();
			assertThat(response.getData().get(0).getPreviews()).isNull();
		}
	}

	@Test
	@DisplayName("A caller without READ_PIPELINE_RUN cannot fetch preview bytes")
	void testPreviewBytesRequirePermission() throws Exception {
		// The bytes must be exactly as reachable as the record that pointed at them, no more.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			PipelineRunItem item = createItem();
			PipelineRun run = runOf(item);
			PipelineNodeTask task = addTask(item, "pn2", "thumbnail", 0, "DONE",
				payload("thumbnail", "artifact/image", "ONE", "/var/cortex/thumb.jpg"));
			task.setPreviews(storedPreview("thumbnail", new byte[] { 7, 7, 7 }));
			nodeTaskDao().update(task);

			int[] status = new int[1];
			httpGetBytes(vertx, tasksPath(run.getPipelineUuid(), run.getUuid(), item.getUuid())
				+ "/" + task.getUuid() + "/previews/thumbnail", login.getToken(), status, new String[1]);

			assertEquals(403, status[0]);
		} finally {
			vertx.close();
		}
	}

	/** GET returning raw bytes, so a preview can be compared exactly rather than through JSON. */
	private byte[] httpGetBytes(Vertx vertx, String path, String token, int[] statusOut, String[] etagOut)
		throws Exception {
		return httpGetBytes(vertx, path, token, statusOut, etagOut, null);
	}

	private byte[] httpGetBytes(Vertx vertx, String path, String token, int[] statusOut, String[] etagOut,
		String ifNoneMatch) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<byte[]> future = new CompletableFuture<>();

		client.request(HttpMethod.GET, restPort(), "localhost", path)
			.compose(req -> {
				if (token != null) {
					req.putHeader("Authorization", "Bearer " + token);
				}
				if (ifNoneMatch != null) {
					req.putHeader("If-None-Match", ifNoneMatch);
				}
				return req.send();
			})
			.compose(resp -> {
				statusOut[0] = resp.statusCode();
				etagOut[0] = resp.getHeader("ETag");
				return resp.body();
			})
			.onSuccess(buf -> future.complete(buf == null ? new byte[0] : buf.getBytes()))
			.onFailure(future::completeExceptionally);

		return future.get(10, TimeUnit.SECONDS);
	}

	// ── HTTP helper ──────────────────────────────────────────────────────

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
