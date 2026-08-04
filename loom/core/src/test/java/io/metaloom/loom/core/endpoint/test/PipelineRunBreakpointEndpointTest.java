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
 * Verifies the four breakpoint routes under {@code /api/v1/pipelines/:uuid/runs/:runUuid/}.
 *
 * <p>
 * These routes split cleanly in two, and the tests follow the split. The guards - unknown run,
 * wrong pipeline, missing permission, no live engine - are exercised against run rows created
 * straight through the DAOs, which is exactly the shape of a run lost to a restart. The behaviour
 * that actually holds and releases work is exercised against a <em>real registered engine</em>,
 * because the interesting half of every one of these routes is unreachable without one.
 * </p>
 *
 * <p>
 * The routes are run state and must stay that way: nothing here may write a breakpoint into the
 * stored pipeline definition, and {@link #testBreakpointsDoNotTouchTheDefinition} says so.
 * </p>
 */
public class PipelineRunBreakpointEndpointTest {

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
	private PipelineRun createRun(String status) {
		UUID adminUuid = loom.internal().daos().userDao().loadAdmin().getUuid();
		Pipeline pipeline = loom.internal().daos().pipelineDao().createPipeline(adminUuid, "bp-test-" + UUID.randomUUID());
		loom.internal().daos().pipelineDao().store(pipeline);
		PipelineRun run = runDao().createPipelineRun(adminUuid, pipeline.getUuid(), 1);
		run.setStatus(status);
		runDao().store(run);
		return run;
	}

	// ── A real engine, so the routes have something to act on ────────────

	/** src -> hash -> thumb, the smallest graph with a node that has a dependent. */
	private PipelineGraph linearGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media"))
				.add(new JsonObject().put("source", "hash").put("sourcePort", "hash").put("target", "thumb").put("targetPort", "media")));
		return new PipelineGraphParser().parse("bp", definition, true, false, 0);
	}

	/** Accepts everything and remembers the tasks, standing in for a worker fleet. */
	private static class RecordingDispatcher implements NodeDispatcher {

		final List<NodeTask> tasks = new CopyOnWriteArrayList<>();

		@Override
		public String dispatch(NodeTask task) {
			tasks.add(task);
			return "test-worker";
		}

		NodeTask taskFor(String nodeId) {
			return tasks.stream().filter(t -> t.getNodeId().equals(nodeId)).findFirst()
				.orElseThrow(() -> new AssertionError("Nothing was dispatched for node " + nodeId));
		}

		boolean wasDispatched(String nodeId) {
			return tasks.stream().anyMatch(t -> t.getNodeId().equals(nodeId));
		}
	}

	/** A registered, started engine holding one item, ready to be driven by the routes. */
	private record LiveRun(PipelineRun run, PipelineRunEngine engine, RecordingDispatcher dispatcher, String itemId) {
	}

	private LiveRun startLiveRun() {
		PipelineRun run = createRun("RUNNING");
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, run.getUuid());
		loom.internal().pipelineRunRegistry().register(run.getUuid(), engine);
		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		return new LiveRun(run, engine, dispatcher, itemId);
	}

	/** Settle a node the way a worker would, so the engine can decide what happens next. */
	private void settle(LiveRun live, String nodeId, Object value) {
		NodeTask task = live.dispatcher().taskFor(nodeId);
		Map<String, PortPayload> outputs = Map.of(nodeId,
			PortPayload.one("hash/sha512", Origin.single(live.itemId()), value));
		live.engine().onNodeTaskResult(live.itemId(),
			NodeTaskResult.completed(task.getTaskUuid(), nodeId, 5, outputs));
	}

	// ── Paths ────────────────────────────────────────────────────────────

	private String breakpointsPath(UUID pipelineUuid, UUID runUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/breakpoints";
	}

	private String continuePath(UUID pipelineUuid, UUID runUuid, String nodeId) {
		return breakpointsPath(pipelineUuid, runUuid) + "/" + nodeId + "/continue";
	}

	private String stepsPath(UUID pipelineUuid, UUID runUuid) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + runUuid + "/steps";
	}

	// ── Reading what is armed ────────────────────────────────────────────

	@Test
	@DisplayName("A run with no live engine reports nothing armed rather than failing")
	void testListBreakpointsWithoutEngine() throws Exception {
		// A run lost to a restart is genuinely arming nothing and holding nothing. Saying so is
		// more useful than an error the caller would have to special-case.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.GET, breakpointsPath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(200, status[0]);
			assertThat(body.getJsonArray("nodeIds")).isEmpty();
			assertThat(body.getJsonArray("held")).isEmpty();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Reading the breakpoints of an unknown run is a 404")
	void testListBreakpointsUnknownRunIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, breakpointsPath(UUID.randomUUID(), UUID.randomUUID()),
				client.getToken(), null, status);

			assertEquals(404, status[0]);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Reading the breakpoints of a run through the wrong pipeline is a 404")
	void testListBreakpointsWrongPipelineIsNotFound() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, breakpointsPath(UUID.randomUUID(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(404, status[0]);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without READ_PIPELINE_RUN cannot read the breakpoints")
	void testListBreakpointsWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe holds only READ_USER.
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, breakpointsPath(run.getPipelineUuid(), run.getUuid()),
				login.getToken(), null, status);

			assertEquals(403, status[0]);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Reading the breakpoints anonymously is refused")
	void testListBreakpointsAnonymouslyIsRefused() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.GET, breakpointsPath(run.getPipelineUuid(), run.getUuid()),
				null, null, status);

			assertThat(status[0]).as("an unauthenticated read must not be served").isIn(401, 403);
		} finally {
			vertx.close();
		}
	}

	// ── Arming ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("Arming a breakpoint on a live run stores it and reports it back")
	void testArmBreakpoint() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.PUT,
				breakpointsPath(live.run().getPipelineUuid(), live.run().getUuid()), client.getToken(),
				new JsonObject().put("nodeIds", new JsonArray().add("hash")), status);

			assertEquals(200, status[0]);
			assertThat(body.getJsonArray("nodeIds")).containsExactly("hash");
			assertThat(live.engine().getBreakpoints()).containsExactly("hash");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Arming a breakpoint on a node that does not exist is a 400 that names it")
	void testArmUnknownNodeIsBadRequest() throws Exception {
		// A breakpoint on a typo would arm silently and never fire, and the operator would
		// conclude the feature is broken rather than that they mistyped.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.PUT,
				breakpointsPath(live.run().getPipelineUuid(), live.run().getUuid()), client.getToken(),
				new JsonObject().put("nodeIds", new JsonArray().add("hahs")), status);

			assertEquals(400, status[0]);
			assertThat(body.encode()).as("the message must name the offending id").contains("hahs");
			assertThat(live.engine().getBreakpoints()).as("a rejected request must arm nothing").isEmpty();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Arming breakpoints on a run with no live engine is a 409")
	void testArmWithoutLiveEngineIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.PUT, breakpointsPath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), new JsonObject().put("nodeIds", new JsonArray().add("hash")), status);

			assertEquals(409, status[0], "Accepting a breakpoint nothing will ever honour would be a lie");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without UPDATE_PIPELINE_RUN cannot arm a breakpoint")
	void testArmWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			LiveRun live = startLiveRun();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.PUT,
				breakpointsPath(live.run().getPipelineUuid(), live.run().getUuid()), login.getToken(),
				new JsonObject().put("nodeIds", new JsonArray().add("hash")), status);

			assertEquals(403, status[0]);
			assertThat(live.engine().getBreakpoints()).as("a forbidden request must arm nothing").isEmpty();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("An empty node list disarms everything")
	void testEmptyListDisarms() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();
			live.engine().setBreakpoints(List.of("hash"));

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.PUT,
				breakpointsPath(live.run().getPipelineUuid(), live.run().getUuid()), client.getToken(),
				new JsonObject().put("nodeIds", new JsonArray()), status);

			assertEquals(200, status[0]);
			assertThat(body.getJsonArray("nodeIds")).isEmpty();
			assertThat(live.engine().getBreakpoints()).isEmpty();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Arming a breakpoint does not touch the stored pipeline definition")
	void testBreakpointsDoNotTouchTheDefinition() throws Exception {
		// The trap named in the plan: breakpoints are run state. If they ever reached the
		// definition, debugging a run would change the pipeline everyone else runs.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();
			Pipeline before = loom.internal().daos().pipelineDao().load(live.run().getPipelineUuid());
			UUID latestVersionBefore = before.getLatestVersionUuid();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.PUT,
				breakpointsPath(live.run().getPipelineUuid(), live.run().getUuid()), client.getToken(),
				new JsonObject().put("nodeIds", new JsonArray().add("hash")), status);
			assertEquals(200, status[0]);

			Pipeline after = loom.internal().daos().pipelineDao().load(live.run().getPipelineUuid());
			assertThat(after.getLatestVersionUuid())
				.as("arming a breakpoint must not create a new pipeline version")
				.isEqualTo(latestVersionBefore);
		} finally {
			vertx.close();
		}
	}

	// ── Holding and releasing ────────────────────────────────────────────

	@Test
	@DisplayName("A held execution shows up in the breakpoint response and blocks its dependent")
	void testHeldExecutionIsReported() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();
			live.engine().setBreakpoints(List.of("hash"));

			settle(live, "hash", "abc");

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.GET,
				breakpointsPath(live.run().getPipelineUuid(), live.run().getUuid()), client.getToken(), null, status);

			assertEquals(200, status[0]);
			JsonArray held = body.getJsonArray("held");
			assertThat(held).hasSize(1);
			JsonObject entry = held.getJsonObject(0);
			assertThat(entry.getString("nodeId")).isEqualTo("hash");
			assertThat(entry.getString("itemUuid")).isEqualTo(live.itemId());
			assertThat(entry.getInteger("elementSeq")).isZero();
			assertThat(live.dispatcher().wasDispatched("thumb"))
				.as("the dependent must not have been dispatched")
				.isFalse();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Continue releases the node's holds and lets the run carry on")
	void testContinueReleasesTheNode() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();
			live.engine().setBreakpoints(List.of("hash"));
			settle(live, "hash", "abc");
			assertThat(live.dispatcher().wasDispatched("thumb")).isFalse();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				continuePath(live.run().getPipelineUuid(), live.run().getUuid(), "hash"), client.getToken(), null, status);

			assertEquals(200, status[0]);
			assertThat(live.engine().heldCount()).isZero();
			assertThat(live.dispatcher().wasDispatched("thumb"))
				.as("continuing must actually dispatch what was held back")
				.isTrue();
			assertThat(live.engine().getBreakpoints())
				.as("continue releases, it does not disarm")
				.containsExactly("hash");
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Continue on a run with no live engine is a 409")
	void testContinueWithoutLiveEngineIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, continuePath(run.getPipelineUuid(), run.getUuid(), "hash"),
				client.getToken(), null, status);

			assertEquals(409, status[0]);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without UPDATE_PIPELINE_RUN cannot continue")
	void testContinueWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			LiveRun live = startLiveRun();
			live.engine().setBreakpoints(List.of("hash"));
			settle(live, "hash", "abc");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				continuePath(live.run().getPipelineUuid(), live.run().getUuid(), "hash"), login.getToken(), null, status);

			assertEquals(403, status[0]);
			assertThat(live.engine().heldCount()).as("a forbidden continue must release nothing").isEqualTo(1);
		} finally {
			vertx.close();
		}
	}

	// ── Stepping ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("A step releases exactly one held execution")
	void testStepReleasesOne() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();
			live.engine().setBreakpoints(List.of("hash"));
			settle(live, "hash", "abc");

			int[] status = new int[1];
			JsonObject body = httpSend(vertx, HttpMethod.POST,
				stepsPath(live.run().getPipelineUuid(), live.run().getUuid()), client.getToken(), null, status);

			assertEquals(200, status[0]);
			assertThat(body.getJsonArray("held")).as("the response shows what is left holding").isEmpty();
			assertThat(live.dispatcher().wasDispatched("thumb")).isTrue();
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Stepping a run that is not holding anything is a 409, not a silent no-op")
	void testStepWithNothingHeldIsConflict() throws Exception {
		// "Step" that quietly did nothing looks identical to "step" that advanced the run,
		// which is the one ambiguity a debugger cannot afford.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LiveRun live = startLiveRun();

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, stepsPath(live.run().getPipelineUuid(), live.run().getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0]);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("Stepping a run with no live engine is a 409")
	void testStepWithoutLiveEngineIsConflict() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineRun run = createRun("RUNNING");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, stepsPath(run.getPipelineUuid(), run.getUuid()),
				client.getToken(), null, status);

			assertEquals(409, status[0]);
		} finally {
			vertx.close();
		}
	}

	@Test
	@DisplayName("A caller without UPDATE_PIPELINE_RUN cannot step")
	void testStepWithoutPermissionIsForbidden() throws Exception {
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
			LiveRun live = startLiveRun();
			live.engine().setBreakpoints(List.of("hash"));
			settle(live, "hash", "abc");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST, stepsPath(live.run().getPipelineUuid(), live.run().getUuid()),
				login.getToken(), null, status);

			assertEquals(403, status[0]);
			assertThat(live.engine().heldCount()).isEqualTo(1);
		} finally {
			vertx.close();
		}
	}

	// ── Event broadcast ──────────────────────────────────────────────────

	@Test
	@DisplayName("A hold and its release are announced, with everything the editor needs to ring the node")
	void testHoldAndReleaseAreAnnounced() throws Exception {
		// Announced immediately rather than on the NODE_STATS tick: a hold happens because a person
		// asked for it, and an editor that learned about it a second late would leave the node
		// looking like it was still running.
		//
		// This observes what the engine hands the listener, not what comes back out of the UI
		// WebSocket. Delivery over that socket is deliberately NOT asserted here - see the note on
		// the recorder below.
		Vertx vertx = Vertx.vertx();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			List<String> announced = new CopyOnWriteArrayList<>();
			LiveRun live = startLiveRunRecordingBreakpoints(announced);

			live.engine().setBreakpoints(List.of("hash"));
			settle(live, "hash", "abc");

			// Assert the hold before the announcement, so a failure says which of the two broke.
			// Reported as one string it would say "no announcement" whether the engine never held
			// or held silently, and those have nothing to do with each other.
			assertThat(live.engine().heldCount())
				.as("the engine must actually be holding before anything can be announced")
				.isEqualTo(1);

			// Every field the editor needs: which node to ring, for which item and which element,
			// and a file name a person can recognise.
			assertThat(announced)
				.as("a hold must announce itself")
				.containsExactly("HELD hash " + live.itemId() + " 0 /media/a.mp4");

			int[] status = new int[1];
			httpSend(vertx, HttpMethod.POST,
				continuePath(live.run().getPipelineUuid(), live.run().getUuid(), "hash"), client.getToken(), null, status);
			assertEquals(200, status[0]);

			assertThat(announced)
				.as("a release must announce itself too, or the node stays ringed forever")
				.contains("RELEASED hash " + live.itemId() + " 0 /media/a.mp4");
		} finally {
			vertx.close();
		}
	}

	/**
	 * As {@link #startLiveRun()}, but recording what the engine hands its breakpoint listener.
	 *
	 * <p>
	 * This records the listener's arguments rather than reading frames back off the UI WebSocket,
	 * and the reason is worth knowing before anyone tries to "restore" the socket assertion.
	 * <b>A broadcast published from test code never reaches a subscriber of the running server.</b>
	 * {@code loom.internal().pipelineEventBroadcaster()} hands back a different instance from the
	 * one the live REST server registered the socket on, so its {@code subscribers} map is empty
	 * and {@code broadcast} returns having sent nothing - silently, with no dropped-event counter
	 * to show for it. A probe frame of an unrelated type behaved identically, which is what ruled
	 * the breakpoint path out.
	 * </p>
	 *
	 * <p>
	 * This is why {@code PipelineRunPauseEndpointTest} <em>can</em> assert real frames: its
	 * broadcasts originate <em>inside</em> the server, on the REST route. The equivalent for
	 * breakpoints would need a run dispatched through {@code POST /run} so that
	 * {@code attachBreakpointBroadcast} wires the engine up, and that needs a registered worker.
	 * </p>
	 *
	 * <p>
	 * <b>So end-to-end delivery over {@code /pipelines/events/ws} is not covered by this class.</b>
	 * What is covered is that a hold and a release each announce themselves exactly once, carrying
	 * the node, item, element and media path an editor needs. The frames themselves are covered from
	 * the other side, against a real socket, by {@code loom-ui/e2e/pipeline-breakpoints-mocked.spec.ts}.
	 * </p>
	 */
	private LiveRun startLiveRunRecordingBreakpoints(List<String> announced) {
		PipelineRun run = createRun("RUNNING");
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(linearGraph(), dispatcher, run.getUuid());
		engine.onBreakpoint((itemId, mediaPath, nodeId, elementSeq, held) -> announced
			.add((held ? "HELD " : "RELEASED ") + nodeId + " " + itemId + " " + elementSeq + " " + mediaPath));
		loom.internal().pipelineRunRegistry().register(run.getUuid(), engine);
		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		return new LiveRun(run, engine, dispatcher, itemId);
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
