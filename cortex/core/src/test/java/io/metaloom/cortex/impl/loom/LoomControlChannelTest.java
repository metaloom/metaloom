package io.metaloom.cortex.impl.loom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.cortex.common.metrics.NoopCortexMetrics;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.SourceItemsAckMessage;
import io.metaloom.loom.rest.model.processor.message.SourceTaskMessage;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The worker's half of the Loom control channel, against a real websocket.
 *
 * <p>
 * This seam had no direct test, and it is the one every online worker depends on: everything Loom
 * knows about a worker arrives through it, and every unit of work leaves through it. The three
 * things that can silently go wrong are what is pinned here — a frame type routed to the wrong
 * handler (or to none), a disconnect that never schedules a reconnect, and a
 * {@code NODE_REGISTRATION} that announces something other than what this worker can actually run.
 * All three fail invisibly in production: the worker stays connected and simply does less.
 * </p>
 *
 * <p>
 * The counterparty is a real Vert.x websocket server rather than a mock socket, so the frames under
 * test are the encoded ones. A stubbed {@code WebSocket} would have proved that the channel calls
 * {@code writeTextMessage}, which is not the part that breaks.
 * </p>
 */
public class LoomControlChannelTest {

	private static final String WS_PATH = "/api/v1/processors/ws";

	/** Node ids that really are on this module's test classpath, so their contracts harvest. */
	private static final Set<String> REGISTERED_TYPES = Set.of("sha512", "sha256", "md5", "filesystem-source");

	private Vertx vertx;
	private HttpServer server;
	private int port;

	/** Frames the fake Loom received, in arrival order. */
	private final List<ProcessorMessage> received = new CopyOnWriteArrayList<>();
	private final AtomicInteger connectionCount = new AtomicInteger();
	private volatile ServerWebSocket serverSocket;
	private volatile String lastPath;
	private volatile String lastQuery;

	private RecordingMetrics metrics;
	private RecordingTaskHandler taskHandler;
	private CortexOptions options;
	private LoomControlChannel channel;

	@BeforeEach
	void startFakeLoom() throws Exception {
		vertx = Vertx.vertx();
		metrics = new RecordingMetrics();
		taskHandler = new RecordingTaskHandler();
		server = vertx.createHttpServer()
			.webSocketHandler(ws -> {
				connectionCount.incrementAndGet();
				lastPath = ws.path();
				lastQuery = ws.query();
				serverSocket = ws;
				ws.textMessageHandler(text -> received.add(Json.decodeValue(text, ProcessorMessage.class)));
			})
			.listen(0)
			.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
		port = server.actualPort();

		options = new CortexOptions()
			.setNodeId("worker-a")
			.setMonitoringPort(8081)
			.setLoom(new LoomClientOptions().setHostname("localhost").setPort(port));
	}

	@AfterEach
	void stopFakeLoom() throws Exception {
		if (channel != null) {
			channel.stop();
		}
		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
		}
		if (vertx != null) {
			vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
		}
	}

	// ── Harness ───────────────────────────────────────────────────────────

	private LoomControlChannel start() {
		channel = new LoomControlChannel(vertx, options, taskHandler, metrics, LoomControlChannelTest::stubFactory);
		channel.start();
		return channel;
	}

	/**
	 * A factory that knows which kinds it offers but refuses to build one: the channel must consult
	 * it only for {@link NodeFactory#registeredTypes()}, never to instantiate a node.
	 */
	private static NodeFactory stubFactory() {
		return new NodeFactory() {

			@Override
			public PipelineNode createNode(JsonObject nodeDef) {
				throw new UnsupportedOperationException("The control channel must never build a node itself");
			}

			@Override
			public Set<String> registeredTypes() {
				return REGISTERED_TYPES;
			}
		};
	}

	/** Poll rather than sleep a fixed time, so the test is not a race on a loaded machine. */
	private static void await(String what, Supplier<Boolean> condition) {
		for (int i = 0; i < 500; i++) {
			if (condition.get()) {
				return;
			}
			sleep(10);
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private ProcessorMessage awaitFrame(ProcessorMessageType type) {
		await("a " + type + " frame, got " + types(), () -> frame(type) != null);
		return frame(type);
	}

	private ProcessorMessage frame(ProcessorMessageType type) {
		for (ProcessorMessage message : received) {
			if (message.getType() == type) {
				return message;
			}
		}
		return null;
	}

	private List<ProcessorMessageType> types() {
		List<ProcessorMessageType> list = new ArrayList<>();
		received.forEach(m -> list.add(m.getType()));
		return list;
	}

	/** Speak as Loom would. */
	private void loomSends(ProcessorMessageType type) {
		loomSends(new ProcessorMessage(type));
	}

	private void loomSends(ProcessorMessage message) {
		await("the worker to connect", () -> serverSocket != null);
		serverSocket.writeTextMessage(Json.encode(message));
	}

	/** Register the worker the way Loom does, and wait until it has taken effect. */
	private void completeRegistration() {
		awaitFrame(ProcessorMessageType.REGISTER);
		loomSends(ProcessorMessageType.REGISTERED);
		await("the channel to become ready", () -> channel.isReady());
	}

	// ── Registration ──────────────────────────────────────────────────────

	@Test
	void testTheRegisterFrameNamesTheWorkerAndWhatItWillRun() {
		start();

		JsonObject body = awaitFrame(ProcessorMessageType.REGISTER).getBody();
		assertEquals("worker-a", body.getString("nodeId"));
		assertEquals(Set.copyOf(REGISTERED_TYPES), setOf(body.getJsonArray("nodeWhitelist")),
			"With nothing configured, a worker advertises exactly what its node factory has - "
				+ "advertising more means work dispatched here that cannot be run");
	}

	@Test
	void testAConfiguredWhitelistNarrowsWhatIsAdvertised() {
		options.setNodeWhitelist(Set.of("sha512", "md5"));
		start();

		JsonObject body = awaitFrame(ProcessorMessageType.REGISTER).getBody();
		assertEquals(Set.of("sha512", "md5"), setOf(body.getJsonArray("nodeWhitelist")));
	}

	@Test
	void testTheWorkerConnectsToTheProcessorEndpointAndCarriesItsToken() {
		options.getLoom().setToken("s3cret token");
		start();

		awaitFrame(ProcessorMessageType.REGISTER);
		assertEquals(WS_PATH, lastPath);
		assertEquals("token=s3cret+token", lastQuery, "The token must be URL-encoded into the connect URI");
	}

	@Test
	void testAWorkerWithNoTokenConnectsWithoutAQueryString() {
		start();

		awaitFrame(ProcessorMessageType.REGISTER);
		assertEquals(WS_PATH, lastPath);
		assertTrue(lastQuery == null || lastQuery.isEmpty(), "Got a query string: " + lastQuery);
	}

	// ── Node announcement ─────────────────────────────────────────────────

	/**
	 * The announcement is the palette Loom offers an author, so it has to be exactly what this
	 * worker can execute. Announcing more puts a node in the editor that fails at dispatch;
	 * announcing less loses it from the editor while the worker still runs it.
	 */
	@Test
	void testNodeRegistrationAnnouncesTheRunnableSetAfterWhitelistAndBlacklist() {
		options.setNodeWhitelist(Set.of("sha512", "md5", "tika"));
		options.setNodeBlacklist(Set.of("md5"));
		start();
		completeRegistration();

		ProcessorMessage announcement = awaitFrame(ProcessorMessageType.NODE_REGISTRATION);
		assertEquals("worker-a", announcement.getBody().getString("cortexId"));

		// 'tika' is whitelisted but the factory does not offer it; 'md5' is blacklisted. Neither is
		// runnable, so neither may be announced.
		assertEquals(Set.of("sha512"), announcedNodeIds(announcement));
	}

	@Test
	void testTheAnnouncementFollowsRegistrationRatherThanRidingOnIt() {
		start();

		awaitFrame(ProcessorMessageType.REGISTER);
		// Registration is a cheap in-memory operation on Loom's side; ingesting contracts writes to
		// Postgres. Sending both together would turn a reconnect storm into a database problem.
		sleep(200);
		assertNull(frame(ProcessorMessageType.NODE_REGISTRATION),
			"Contracts must not be announced before Loom has acknowledged the registration");

		loomSends(ProcessorMessageType.REGISTERED);
		assertNotNull(awaitFrame(ProcessorMessageType.NODE_REGISTRATION));
	}

	@Test
	void testAnnouncementCanBeTurnedOffWithoutAffectingWhatTheWorkerRuns() {
		options.setNodeSpecAnnounceEnabled(false);
		start();
		completeRegistration();

		sleep(200);
		assertNull(frame(ProcessorMessageType.NODE_REGISTRATION));
		assertTrue(channel.isReady(), "A worker that announces nothing is still fully registered and placeable");
	}

	@Test
	void testARejectedAnnouncementDoesNotUnregisterTheWorker() {
		// Loom refusing a contract must be a log line, not a state change: the worker keeps running
		// exactly the same work, it just does not contribute a palette entry.
		start();
		completeRegistration();
		awaitFrame(ProcessorMessageType.NODE_REGISTRATION);

		loomSends(new ProcessorMessage(ProcessorMessageType.NODE_REGISTRATION_ACK, new JsonObject()
			.put("cortexId", "worker-a")
			.put("accepted", new JsonArray())
			.put("rejected", new JsonArray().add(new JsonObject()
				.put("nodeId", "sha512")
				.put("reason", "BUILTIN")
				.put("message", "Loom ships its own contract for this node")))));

		sleep(200);
		assertTrue(channel.isReady());
	}

	private Set<String> announcedNodeIds(ProcessorMessage announcement) {
		JsonArray descriptors = announcement.getBody().getJsonArray("nodes");
		assertNotNull(descriptors, "A NODE_REGISTRATION must carry the node contracts: " + announcement.getBody());
		Set<String> ids = new LinkedHashSet<>();
		for (int i = 0; i < descriptors.size(); i++) {
			ids.add(descriptors.getJsonObject(i).getString("nodeId"));
		}
		return ids;
	}

	private static Set<String> setOf(JsonArray array) {
		assertNotNull(array, "Expected an array, got null");
		Set<String> values = new LinkedHashSet<>();
		array.forEach(value -> values.add(String.valueOf(value)));
		return values;
	}

	// ── Frame routing ─────────────────────────────────────────────────────

	/**
	 * One case per inbound type. A type routed to the wrong handler - or dropped into the
	 * {@code default} branch - leaves the worker connected, acknowledged and idle, which is the
	 * hardest failure of this class to notice.
	 */
	@Test
	void testEachInboundFrameReachesItsHandler() {
		start();
		completeRegistration();

		UUID runUuid = UUID.randomUUID();
		loomSends(new ProcessorMessage(ProcessorMessageType.NODE_TASK, JsonObject.mapFrom(
			new NodeTask(UUID.randomUUID(), runUuid, "item-1", "n1", "sha512", MediaRef.of("/media/a.mp4"),
				Map.of(), Map.of()))));
		loomSends(new ProcessorMessage(ProcessorMessageType.SEGMENT_TASK, JsonObject.mapFrom(
			new SegmentTask(UUID.randomUUID(), runUuid, "item-2", "seg-1", "gpu", MediaRef.of("/media/b.mp4"),
				List.of(), Map.of()))));
		loomSends(new ProcessorMessage(ProcessorMessageType.SOURCE_TASK, JsonObject.mapFrom(
			new SourceTaskMessage().setRunUuid(runUuid).setNodeId("n0").setNodeKind("filesystem-source"))));
		loomSends(new ProcessorMessage(ProcessorMessageType.SOURCE_ITEMS_ACK, JsonObject.mapFrom(
			new SourceItemsAckMessage().setRunUuid(runUuid).setSeq(7))));

		await("every task frame to be routed", () -> taskHandler.nodeTasks.size() == 1
			&& taskHandler.segmentTasks.size() == 1
			&& taskHandler.sourceTasks.size() == 1
			&& taskHandler.sourceAcks.size() == 1);

		assertEquals("item-1", taskHandler.nodeTasks.get(0).getItemId());
		assertEquals("seg-1", taskHandler.segmentTasks.get(0).getSegmentId());
		assertEquals("filesystem-source", taskHandler.sourceTasks.get(0).getNodeKind());
		assertEquals(runUuid + "#7", taskHandler.sourceAcks.get(0));
	}

	@Test
	void testHeartbeatAckAndErrorFramesLandInTheHealthReport() {
		start();
		completeRegistration();

		assertNull(channel.healthStatus().getLong("lastHeartbeatAckAt"));
		loomSends(ProcessorMessageType.HEARTBEAT_ACK);
		await("the heartbeat ack to be recorded", () -> channel.healthStatus().getLong("lastHeartbeatAckAt") != null);

		loomSends(new ProcessorMessage(ProcessorMessageType.ERROR,
			new JsonObject().put("message", "unknown run")));
		await("the error to be surfaced",
			() -> "unknown run".equals(channel.healthStatus().getString("error")));
	}

	/**
	 * A frame the worker cannot use must cost it the frame, not the connection. Every one of these
	 * would previously have had to be caught by an exception handler somewhere further out, which is
	 * how a single bad payload takes a worker offline until it reconnects.
	 */
	@Test
	void testUnusableFramesAreDroppedWithoutLosingTheConnection() {
		start();
		completeRegistration();

		// Not JSON at all.
		serverSocket.writeTextMessage("}{ not json");
		// A known type whose body is missing.
		loomSends(ProcessorMessageType.NODE_TASK);
		// A known type whose body is the wrong shape.
		loomSends(new ProcessorMessage(ProcessorMessageType.SEGMENT_TASK,
			new JsonObject().put("taskUuid", "not-a-uuid")));
		// A type this worker has no case for.
		loomSends(ProcessorMessageType.PIPELINE_EVENT);

		sleep(300);
		assertEquals(0, taskHandler.nodeTasks.size(), "A NODE_TASK without a body is not a task");
		assertEquals(0, taskHandler.segmentTasks.size(), "A SEGMENT_TASK that will not parse is not a task");
		assertTrue(channel.isReady(), "None of these may take the worker offline");

		// Still serving: a well-formed frame after the bad ones is handled normally.
		loomSends(new ProcessorMessage(ProcessorMessageType.SOURCE_ITEMS_ACK, JsonObject.mapFrom(
			new SourceItemsAckMessage().setRunUuid(UUID.randomUUID()).setSeq(1))));
		await("the connection to still be serving frames", () -> taskHandler.sourceAcks.size() == 1);
	}

	// ── Reconnect ─────────────────────────────────────────────────────────

	/**
	 * A dropped connection has to schedule its own reconnect: nothing else will. The worker stays
	 * {@code started}, so it must come back and re-register - and the re-registration is also what
	 * re-announces its contracts, because Loom drops the announced links for a worker it has not
	 * heard from.
	 */
	@Test
	void testADroppedConnectionReconnectsAndReRegisters() {
		start();
		completeRegistration();
		awaitFrame(ProcessorMessageType.NODE_REGISTRATION);
		assertEquals(1, connectionCount.get());

		received.clear();
		serverSocket.close();

		await("the channel to notice the drop", () -> !channel.isReady());
		assertTrue(metrics.reconnects.get() >= 1, "A scheduled reconnect must be counted");
		assertTrue(channel.healthStatus().getLong("reconnectAttempts") >= 1L);

		// The base backoff is 2s, so this is the one test here that waits on a timer.
		await("the worker to reconnect", () -> connectionCount.get() == 2);
		awaitFrame(ProcessorMessageType.REGISTER);

		loomSends(ProcessorMessageType.REGISTERED);
		assertNotNull(awaitFrame(ProcessorMessageType.NODE_REGISTRATION),
			"Contracts must be re-announced on every reconnect - Loom drops them for a worker it "
				+ "has not heard from");
		assertEquals(0L, channel.healthStatus().getLong("reconnectAttempts").longValue(),
			"A completed registration resets the backoff, so the next outage starts at the base delay");
	}

	@Test
	void testAStoppedChannelDoesNotReconnect() {
		start();
		completeRegistration();

		channel.stop();
		serverSocket.close();

		sleep(2_500);
		assertEquals(1, connectionCount.get(), "stop() is final - a closed socket must not be chased");
		assertFalse(channel.isReady());
	}

	// ── Configuration guards ──────────────────────────────────────────────

	@Test
	void testAnUnconfiguredEndpointDisablesTheChannelRatherThanFailing() {
		options.setLoom(new LoomClientOptions());
		start();

		assertFalse(channel.isReady());
		assertFalse(channel.healthStatus().getBoolean("configured"));
		assertEquals("LOOM host not configured", channel.healthStatus().getString("error"));
		assertEquals(0, connectionCount.get());
	}

	/**
	 * Loom keys registration, leases and attribution on the node id, so a blank one would churn a
	 * fresh instance on every restart or collide with a live worker. Going online without one is
	 * refused rather than papered over with a generated id.
	 */
	@Test
	void testGoingOnlineWithoutANodeIdIsRefused() {
		options.setNodeId("  ");
		channel = new LoomControlChannel(vertx, options, taskHandler, metrics, LoomControlChannelTest::stubFactory);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> channel.start());
		assertTrue(e.getMessage().contains("CORTEX_NODE_ID"), e.getMessage());
		assertEquals(0, connectionCount.get());
	}

	// ── Metrics ───────────────────────────────────────────────────────────

	/**
	 * The gauges {@code spec/features/ops/METRICS.md} §4 attributes to this class. A gauge that
	 * stops being bound scrapes as absent, which reads on a dashboard as "no workers" rather than
	 * "no metric".
	 */
	@Test
	void testTheGaugesTheMetricsCatalogAttributesToThisClassAreBound() {
		start();
		awaitFrame(ProcessorMessageType.REGISTER);

		assertEquals(Set.of(
			"cortex_loom_connected",
			"cortex_loom_registered",
			"cortex_loom_reconnect_attempts",
			"cortex_memory_used_bytes",
			"cortex_memory_max_bytes",
			"cortex_cpu_load",
			"cortex_io_load",
			"cortex_disk_used_bytes",
			"cortex_disk_total_bytes"), metrics.gauges.keySet());
	}

	@Test
	void testTheConnectionGaugesTrackTheLiveState() {
		start();

		await("the connection gauge to go up", () -> metrics.gauge("cortex_loom_connected").intValue() == 1);
		assertEquals(0, metrics.gauge("cortex_loom_registered").intValue(),
			"Connected is not registered - Loom has not acknowledged anything yet");

		completeRegistration();
		assertEquals(1, metrics.gauge("cortex_loom_registered").intValue());

		channel.stop();
		assertEquals(0, metrics.gauge("cortex_loom_connected").intValue());
		assertEquals(0, metrics.gauge("cortex_loom_registered").intValue());
	}

	/** Every inbound frame is counted under its lowercased type, including ones with no case. */
	@Test
	void testEveryInboundFrameIsCountedUnderItsType() {
		start();
		completeRegistration();

		loomSends(ProcessorMessageType.HEARTBEAT_ACK);
		loomSends(ProcessorMessageType.PIPELINE_EVENT);

		await("both frames to be counted", () -> metrics.loomMessages.containsKey("heartbeat_ack")
			&& metrics.loomMessages.containsKey("pipeline_event"));
		assertEquals(1, metrics.loomMessages.get("registered"));
		assertEquals(1, metrics.loomMessages.get("heartbeat_ack"));
		assertEquals(1, metrics.loomMessages.get("pipeline_event"),
			"A type the worker ignores is still a message it received");
	}

	// ── Fakes ─────────────────────────────────────────────────────────────

	/**
	 * Records what the channel handed over. Subclassing the real handler rather than introducing an
	 * interface keeps the production wiring untouched - the point of the test is which method the
	 * channel calls, not what the handler then does.
	 */
	private static class RecordingTaskHandler extends PipelineTaskHandler {

		final List<NodeTask> nodeTasks = new CopyOnWriteArrayList<>();
		final List<SegmentTask> segmentTasks = new CopyOnWriteArrayList<>();
		final List<SourceTaskMessage> sourceTasks = new CopyOnWriteArrayList<>();
		final List<String> sourceAcks = new CopyOnWriteArrayList<>();

		RecordingTaskHandler() {
			// No media loader: every handler method is overridden below, so nothing here gets as far
			// as building a node or resolving a file.
			super(stubFactory(), null, new MediaReferenceResolver(null), NoopCortexMetrics.INSTANCE);
		}

		@Override
		public void handleNodeTask(NodeTask task, PipelineTaskHandler.MessageSender sender) {
			nodeTasks.add(task);
		}

		@Override
		public void handleSegmentTask(SegmentTask task, PipelineTaskHandler.MessageSender sender) {
			segmentTasks.add(task);
		}

		@Override
		public void handleSourceTask(SourceTaskMessage task, PipelineTaskHandler.MessageSender sender) {
			sourceTasks.add(task);
		}

		@Override
		public void handleSourceItemsAck(UUID runUuid, long seq) {
			sourceAcks.add(runUuid + "#" + seq);
		}
	}

	private static class RecordingMetrics implements CortexMetrics {

		final Map<String, Supplier<Number>> gauges = new LinkedHashMap<>();
		final Map<String, Integer> loomMessages = new LinkedHashMap<>();
		final AtomicInteger reconnects = new AtomicInteger();

		Number gauge(String name) {
			Supplier<Number> supplier = gauges.get(name);
			assertNotNull(supplier, "No gauge named " + name + "; bound: " + gauges.keySet());
			return supplier.get();
		}

		@Override
		public synchronized void bindGauge(String name, Supplier<Number> supplier) {
			gauges.put(name, supplier);
		}

		@Override
		public synchronized void recordLoomMessage(String type) {
			loomMessages.merge(type, 1, Integer::sum);
		}

		@Override
		public void recordReconnect() {
			reconnects.incrementAndGet();
		}

		@Override
		public void recordTaskReceived(String type) {
		}

		@Override
		public void recordTaskCompleted(String type, String state) {
		}

		@Override
		public void recordTaskDuration(String type, long durationMs) {
		}

		@Override
		public void recordTaskReturned(String reason) {
		}

		@Override
		public void recordNodeOperation(String nodeKind, ResultState state, long durationMs) {
		}

		@Override
		public void recordFileMissing() {
		}

		@Override
		public void recordResultsBatchSent(int resultCount) {
		}

		@Override
		public void recordBulkSync(String outcome, int assetCount) {
		}

		@Override
		public void recordSourceItemsEnumerated(long count) {
		}

		@Override
		public void recordSourceAckTimeout() {
		}

		@Override
		public void recordAiCall(String provider, boolean success, long durationMs) {
		}

		@Override
		public void recordAiCacheHit(String provider) {
		}
	}
}
