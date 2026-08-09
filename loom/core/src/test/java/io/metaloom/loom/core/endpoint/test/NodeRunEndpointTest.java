package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.pipeline.PipelineRunKind;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.rest.service.impl.AdhocNodeResultWriter;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.rest.service.impl.AdhocRuns;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies the {@code /api/v1/node-runs} routes.
 *
 * <p>
 * No cortex worker is connected in this environment, which is deliberate rather than a limitation:
 * the interesting failures of an execution API are the ones <em>before</em> a task leaves - an
 * ineligible node, invalid options, a run started by somebody else, a quota - and every one of them
 * has to produce a readable answer rather than a hang or a leaked run row.
 * </p>
 *
 * <p>
 * The regression this file exists to hold is
 * {@link #testPersistWritesTheLedgerUnderAnAdhocNodeIdAndClobbersNothing()}:
 * {@code asset_node_result} is {@code UNIQUE (asset_uuid, node_kind, node_id)}, so an ad-hoc run that
 * reused a pipeline's node id would silently overwrite that pipeline's catalog state.
 * </p>
 */
public class NodeRunEndpointTest extends AbstractEndpointTest {

	private static final String BASE = "/api/v1/node-runs";

	private int restPort() {
		return loom.internal().boot().getRestService().getServer().actualPort();
	}

	/** An asset with a stored binary, which is what makes it runnable against at all. */
	private Asset createRunnableAsset(String filename) {
		UUID adminUuid = adminUuid();
		Asset asset = daos().assetDao().createAsset(adminUuid, SHA512.fromString(randomSha512()),
			"image/jpeg", filename, "test", 1024L);
		daos().assetDao().store(asset);

		AssetBinary binary = daos().assetBinaryDao().createAssetBinary("/data/" + filename, asset.getUuid(), adminUuid,
			io.metaloom.loom.test.data.TestValues.LIBRARY_UUID);
		binary.setMimeType("image/jpeg");
		daos().assetBinaryDao().store(binary);
		return asset;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	private JsonObject graphDefinition(String kind) {
		return new JsonObject()
			.put("version", 1)
			.put("name", "test run")
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "n1").put("type", kind)));
	}

	// ── Permissions ──────────────────────────────────────────────────────

	@Test
	@DisplayName("Every node-run route is forbidden without EXECUTE_MCP_NODE")
	void testRoutesRequireExecutePermission() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loginPermissionlessClient()) {
			int[] status = new int[1];
			UUID someUuid = UUID.randomUUID();

			httpSend(vertx, HttpMethod.POST, BASE + "/probes", client.getToken(),
				new JsonObject().put("kind", "sha512").put("assetUuid", someUuid.toString()), status);
			assertEquals(403, status[0], "probe must require EXECUTE_MCP_NODE");

			httpSend(vertx, HttpMethod.POST, BASE, client.getToken(),
				new JsonObject().put("definition", graphDefinition("sha512"))
					.put("assetUuids", new JsonArray().add(someUuid.toString())),
				status);
			assertEquals(403, status[0], "start must require EXECUTE_MCP_NODE");

			httpSend(vertx, HttpMethod.GET, BASE, client.getToken(), null, status);
			assertEquals(403, status[0], "list must require EXECUTE_MCP_NODE");

			httpSend(vertx, HttpMethod.GET, BASE + "/" + someUuid, client.getToken(), null, status);
			assertEquals(403, status[0], "load must require EXECUTE_MCP_NODE");

			httpSend(vertx, HttpMethod.POST, BASE + "/" + someUuid + "/cancel", client.getToken(), null, status);
			assertEquals(403, status[0], "cancel must require EXECUTE_MCP_NODE");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("EXECUTE_MCP_NODE alone is enough to reach the routes")
	void testExecutePermissionIsSufficient() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loginClientWith("noderunner", Permission.EXECUTE_MCP_NODE)) {
			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, BASE, client.getToken(), null, status);
			assertEquals(200, status[0], "a caller holding EXECUTE_MCP_NODE must be able to list their runs");
		} finally {
			vertx.close();
		}
	}

	// ── Probe ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("An unknown node kind is a readable result, not an error")
	void testUnknownKindIsAReadableResult() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = createRunnableAsset("beach.jpg");

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, BASE + "/probes", client.getToken(),
				new JsonObject().put("kind", "there-is-no-such-node").put("assetUuid", asset.getUuid().toString()), status);

			// The request was well formed; the answer is "that node does not exist". A 4xx here would
			// tell a caller to fix its request rather than its choice of node.
			assertEquals(200, status[0], "a rejected probe is a result, not an HTTP error");
			assertEquals("REJECTED", body.getString("state"));
			assertThat(body.getString("message")).contains("there-is-no-such-node");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A byte-producing node is refused before anything is dispatched")
	void testByteProducingKindIsIneligible() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = createRunnableAsset("beach2.jpg");

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, BASE + "/probes", client.getToken(),
				new JsonObject().put("kind", "thumbnail").put("assetUuid", asset.getUuid().toString()), status);

			assertEquals(200, status[0]);
			assertEquals("REJECTED", body.getString("state"));
			// Bytes written by a node stay on the worker, so a caller would be told "success" and handed
			// nothing. Saying why is what stops the caller retrying.
			assertThat(body.getString("message")).containsIgnoringCase("bytes");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An asset with no stored binary is refused with a reason")
	void testAssetWithoutBinaryIsRefused() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = daos().assetDao().createAsset(adminUuid(), SHA512.fromString(randomSha512()),
				"image/jpeg", "nobinary.jpg", "test", 1L);
			daos().assetDao().store(asset);

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, BASE + "/probes", client.getToken(),
				new JsonObject().put("kind", "sha512").put("assetUuid", asset.getUuid().toString()), status);

			assertEquals(200, status[0]);
			assertEquals("REJECTED", body.getString("state"));
			assertThat(body.getString("message")).contains("no stored binary");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("With no worker for the kind the probe says so instead of hanging")
	void testNoWorkerIsReported() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = createRunnableAsset("beach3.jpg");

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, BASE + "/probes", client.getToken(),
				new JsonObject().put("kind", "sha512").put("assetUuid", asset.getUuid().toString()), status);

			assertEquals(200, status[0]);
			assertEquals("REJECTED", body.getString("state"));
			assertThat(body.getString("message")).contains("No worker currently advertises");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A refused probe writes nothing to the ledger")
	void testRefusedProbeWritesNoLedgerRow() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = createRunnableAsset("beach4.jpg");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, BASE + "/probes", client.getToken(),
				new JsonObject().put("kind", "sha512").put("assetUuid", asset.getUuid().toString())
					.put("persist", true),
				status);

			assertThat(daos().assetNodeResultDao().loadByAsset(asset.getUuid()))
				.as("a probe that never ran must record nothing, persist or not")
				.isEmpty();
		} finally {
			vertx.close();
		}
	}

	// ── Ledger namespacing: the clobbering regression ────────────────────

	@Test
	@DisplayName("An ad-hoc ledger row is namespaced and cannot overwrite a pipeline's row")
	void testPersistWritesTheLedgerUnderAnAdhocNodeIdAndClobbersNothing() {
		Asset asset = createRunnableAsset("clobber.jpg");
		UUID runUuid = UUID.randomUUID();

		// A scheduled pipeline has already recorded that 'sha512' ran on this asset as node 'n1'.
		AssetNodeResult pipelineRow = daos().assetNodeResultDao().createNodeResult(adminUuid(), asset.getUuid(), "sha512", "n1");
		pipelineRow.setState("SUCCESS");
		pipelineRow.setOrigin("COMPUTED");
		pipelineRow.setResultRef(new JsonObject().put("marker", "written-by-the-pipeline"));
		daos().assetNodeResultDao().upsert(pipelineRow);

		// The ad-hoc graph uses the very same node id, which is the trap: the definition is the
		// caller's and nothing stops it naming its node 'n1'.
		AdhocNodeResultWriter writer = new AdhocNodeResultWriter(daos().assetNodeResultDao());
		// A probe path: COMPLETED is the execution vocabulary and the writer translates it into the
		// ledger's verdict vocabulary, and it points at neither a run row nor a task row because a
		// probe persists neither.
		writer.writeProbe(runUuid, adminUuid(), asset.getUuid(), "sha512",
			NodeTaskResult.completed(null, "n1", 0,
				Map.of("hash", PortPayload.one(ContentTypeRegistry.HASH_SHA512, Origin.single("item"), "written-by-the-agent"))));

		AssetNodeResult survivor = daos().assetNodeResultDao().loadByNode(asset.getUuid(), "sha512", "n1");
		assertNotNull(survivor, "the pipeline's ledger row must still exist");
		assertEquals("written-by-the-pipeline", survivor.getResultRef().getString("marker"),
			"an ad-hoc result must never overwrite what a scheduled pipeline recorded");

		String adhocNodeId = AdhocRuns.nodeResultId(runUuid);
		assertThat(adhocNodeId).startsWith(AdhocRuns.NODE_ID_PREFIX);
		AssetNodeResult adhocRow = daos().assetNodeResultDao().loadByNode(asset.getUuid(), "sha512", adhocNodeId);
		assertNotNull(adhocRow, "the ad-hoc result must be recorded under its own namespaced node id");
		assertEquals("written-by-the-agent",
			adhocRow.getResultRef().getJsonObject("hash").getJsonArray("elements").getJsonObject(0).getString("value"));
		assertEquals("SUCCESS", adhocRow.getState(), "the ledger records a verdict, not an execution state");
		// A graph node id is validated against ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$ and cannot contain
		// a colon, which is what makes the namespace collision-proof rather than merely unlikely.
		assertThat(adhocNodeId).contains(":");
	}

	// ── Run lifecycle ────────────────────────────────────────────────────

	@Test
	@DisplayName("A run over assets with no worker is a 503 and creates no run row")
	void testStartWithoutWorkerIs503AndLeavesNoRow() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = createRunnableAsset("run1.jpg");
			long before = countAdhocRuns();

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, BASE, client.getToken(),
				new JsonObject()
					.put("definition", graphDefinition("sha512"))
					.put("assetUuids", new JsonArray().add(asset.getUuid().toString())),
				status);

			assertEquals(503, status[0], "a graph no worker can run must be refused up front");
			assertThat(body.getString("msg", body.encode())).contains("sha512");
			assertEquals(before, countAdhocRuns(), "a refused run must not leave a row behind");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An invalid definition is a 400")
	void testInvalidDefinitionIs400() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = createRunnableAsset("run2.jpg");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, BASE, client.getToken(),
				new JsonObject()
					.put("definition", graphDefinition("there-is-no-such-node"))
					.put("assetUuids", new JsonArray().add(asset.getUuid().toString())),
				status);

			assertEquals(400, status[0], "a definition naming an unknown node kind must be rejected");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Too many assets is a 400 naming the limit")
	void testMaxAssetsIsEnforced() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			JsonArray tooMany = new JsonArray();
			int limit = io.metaloom.loom.api.options.NodeExecOptions.DEFAULT_MAX_ASSETS;
			for (int i = 0; i <= limit; i++) {
				tooMany.add(UUID.randomUUID().toString());
			}

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST, BASE, client.getToken(),
				new JsonObject().put("definition", graphDefinition("sha512")).put("assetUuids", tooMany), status);

			assertEquals(400, status[0]);
			assertThat(body.encode()).contains(String.valueOf(limit));
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A foreign run is not found, not forbidden")
	void testForeignRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		PipelineRun otherUsersRun = daos().pipelineRunDao().createAdhocRun(adminUuid(), graphDefinition("sha512"));
		otherUsersRun.setStatus(PipelineRunStatus.RUNNING);
		daos().pipelineRunDao().store(otherUsersRun);

		try (LoomHttpClient client = loginClientWith("nosyrunner", Permission.EXECUTE_MCP_NODE)) {
			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, BASE + "/" + otherUsersRun.getUuid(), client.getToken(), null, status);
			// 403 would confirm the uuid exists and let a caller enumerate other people's jobs.
			assertEquals(404, status[0], "another user's run must be reported as not found");

			httpSend(vertx, HttpMethod.POST, BASE + "/" + otherUsersRun.getUuid() + "/cancel", client.getToken(), null, status);
			assertEquals(404, status[0], "another user's run must not be cancellable");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A catalog run is not addressable as a node run")
	void testCatalogRunIsNotVisibleAsANodeRun() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			var pipeline = daos().pipelineDao().createPipeline(adminUuid(), "catalog-" + UUID.randomUUID());
			daos().pipelineDao().store(pipeline);
			PipelineRun catalogRun = daos().pipelineRunDao().createPipelineRun(adminUuid(), pipeline.getUuid(), 1);
			catalogRun.setStatus(PipelineRunStatus.RUNNING);
			daos().pipelineRunDao().store(catalogRun);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, BASE + "/" + catalogRun.getUuid(), client.getToken(), null, status);
			assertEquals(404, status[0], "/node-runs must only address ad-hoc runs");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("The listing shows only the caller's own ad-hoc runs, with their definition")
	void testListingIsScopedToTheCaller() throws Exception {
		Vertx vertx = Vertx.vertx();
		PipelineRun adminRun = daos().pipelineRunDao().createAdhocRun(adminUuid(), graphDefinition("sha512"));
		adminRun.setStatus(PipelineRunStatus.SUCCESS);
		daos().pipelineRunDao().store(adminRun);

		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.GET, BASE, client.getToken(), null, status);

			assertEquals(200, status[0]);
			JsonArray data = body.getJsonArray("data");
			assertThat(data).isNotEmpty();
			JsonObject listed = data.stream().map(JsonObject.class::cast)
				.filter(r -> adminRun.getUuid().toString().equals(r.getString("uuid")))
				.findFirst().orElseThrow(() -> new AssertionError("the caller's own run must be listed"));
			assertEquals("SUCCESS", listed.getString("status"));
			assertNotNull(listed.getJsonObject("definition"), "a listing must carry what the run was started with");
		} finally {
			vertx.close();
		}

		try (LoomHttpClient client = loginClientWith("otherrunner", Permission.EXECUTE_MCP_NODE)) {
			int[] status = new int[1];
			Vertx vertx2 = Vertx.vertx();
			try {
				JsonObject body = httpSend(vertx2, HttpMethod.GET, BASE, client.getToken(), null, status);
				assertEquals(200, status[0]);
				// A caller with no ad-hoc runs gets no data array at all - that is how every list response
				// in this API renders an empty page.
				assertThat(body.getJsonArray("data", new JsonArray()).stream().map(JsonObject.class::cast)
					.map(r -> r.getString("uuid")))
						.as("another user's run must not appear in the listing")
						.doesNotContain(adminRun.getUuid().toString());
			} finally {
				vertx2.close();
			}
		}
	}

	@Test
	@DisplayName("An ad-hoc run row carries kind=ADHOC, no pipeline and its definition")
	void testAdhocRunRowShape() {
		JsonObject definition = graphDefinition("sha512");
		PipelineRun run = daos().pipelineRunDao().createAdhocRun(adminUuid(), definition);
		run.setStatus(PipelineRunStatus.RUNNING);
		daos().pipelineRunDao().store(run);

		PipelineRun reloaded = daos().pipelineRunDao().load(run.getUuid());
		assertEquals(PipelineRunKind.ADHOC, reloaded.getKind());
		assertNull(reloaded.getPipelineUuid());
		assertEquals(definition, reloaded.getMeta().getJsonObject(PipelineRun.META_DEFINITION));
	}

	@Test
	@DisplayName("A terminal run cannot be cancelled twice")
	void testCancellingATerminalRunIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		PipelineRun run = daos().pipelineRunDao().createAdhocRun(adminUuid(), graphDefinition("sha512"));
		run.setStatus(PipelineRunStatus.SUCCESS);
		daos().pipelineRunDao().store(run);

		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, BASE + "/" + run.getUuid() + "/cancel", client.getToken(), null, status);

			assertEquals(409, status[0], "a finished run cannot be cancelled");
			assertEquals(PipelineRunStatus.SUCCESS, daos().pipelineRunDao().load(run.getUuid()).getStatus(),
				"a refused cancel must not overwrite the terminal state");
		} finally {
			vertx.close();
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private long countAdhocRuns() {
		List<PipelineRun> runs = daos().pipelineRunDao().loadByStatus(PipelineRunStatus.RUNNING);
		return runs.stream().filter(r -> r.getKind() == PipelineRunKind.ADHOC).count();
	}

	private JsonObject httpSend(Vertx vertx, HttpMethod method, String path, String token, JsonObject body, int[] statusOut)
		throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();

		client.request(method, restPort(), "localhost", path)
			.compose(req -> {
				if (token != null) {
					req.putHeader("Authorization", "Bearer " + token);
				}
				if (body == null) {
					return req.send();
				}
				req.putHeader("Content-Type", "application/json");
				return req.send(Buffer.buffer(body.encode()));
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

		return future.get(30, TimeUnit.SECONDS);
	}
}
