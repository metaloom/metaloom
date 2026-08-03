package io.metaloom.cortex.impl.loom;

import java.net.InetAddress;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.spec.NodeSpecCatalog;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.SystemStatusInfo;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.rest.model.processor.message.NodeRegistration;
import io.metaloom.loom.rest.model.processor.message.NodeRegistrationAck;
import io.metaloom.loom.rest.model.processor.message.NodeRegistrationRejection;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.SourceItemsAckMessage;
import io.metaloom.loom.rest.model.processor.message.SourceTaskMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

@Singleton
public class LoomControlChannel {

	private static final Logger log = LoggerFactory.getLogger(LoomControlChannel.class);

	private static final String PROCESSOR_WS_URI = "/api/v1/processors/ws";
	private static final long HEARTBEAT_INTERVAL_MS = 10_000;
	private static final long STATUS_INTERVAL_MS = 20_000;
	private static final long HEALTH_LOG_INTERVAL_MS = 30_000;
	private static final long RECONNECT_BASE_DELAY_MS = 2_000;
	private static final long RECONNECT_MAX_DELAY_MS = 30_000;
	/** How long a shutdown waits for its last frames to reach the socket. */
	private static final long FLUSH_TIMEOUT_MS = 5_000;

	private final Vertx vertx;
	private final CortexOptions options;
	private final CortexMetrics metrics;

	private final PipelineTaskHandler taskHandler;

	/**
	 * Kept for the life of the channel: I/O utilisation is a rate between consecutive
	 * status updates, so the probe has to remember the previous sample.
	 */
	private final SystemLoadProbe loadProbe = new SystemLoadProbe();

	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean connected = new AtomicBoolean(false);
	private final AtomicBoolean registered = new AtomicBoolean(false);
	private final AtomicLong reconnectAttempts = new AtomicLong(0);

	private final String nodeId;
	/** Lazy so merely constructing the channel does not build every node. */
	private final dagger.Lazy<io.metaloom.cortex.pipeline.loader.NodeFactory> nodeFactory;

	private WebSocketClient webSocketClient;
	private WebSocket websocket;
	private long heartbeatTimerId = -1;
	private long statusTimerId = -1;
	private long healthLogTimerId = -1;
	private long reconnectTimerId = -1;

	private volatile long lastConnectedAt;
	private volatile long lastMessageAt;
	private volatile long lastHeartbeatAckAt;
	private volatile String connectionError;
	private volatile String resolvedHost;
	private volatile int resolvedPort;
	private volatile String resolvedToken;
	private volatile boolean endpointConfigured;

	@Inject
	public LoomControlChannel(Vertx vertx, CortexOptions options,
			PipelineTaskHandler taskHandler, CortexMetrics metrics,
			dagger.Lazy<io.metaloom.cortex.pipeline.loader.NodeFactory> nodeFactory) {
		this.nodeFactory = nodeFactory;
		this.vertx = vertx;
		this.options = options;
		this.metrics = metrics;
		// A configured id survives a restart and Loom keys registration, leases and attribution
		// on it; a generated one would not survive and could collide with a live worker. The id
		// is validated at start() (below), not here: this constructor runs while Dagger builds
		// the command graph for every invocation - including --help and the offline `process`
		// command - so throwing here would break those paths before the CLI can report anything.
		this.nodeId = options.getNodeId();
		this.taskHandler = taskHandler;
	}

	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}

		registerGauges();

		resolveEndpoint();
		if (!endpointConfigured) {
			log.warn("No Loom websocket endpoint configured. Control channel disabled.");
			return;
		}

		// Going online requires a stable identity. CortexMain already refuses to start a worker
		// without one; this guards any other path that reaches an online start, refusing to
		// register under a blank/generated id rather than churning a fresh cortex_instance on
		// every restart or colliding with a live worker.
		if (nodeId == null || nodeId.isBlank()) {
			started.set(false);
			throw new IllegalStateException("No Cortex node id configured. Set CORTEX_NODE_ID to a "
				+ "value that is unique per worker and stable across restarts; Loom keys registration, leases and "
				+ "attribution on it and rejects a blank or duplicate id.");
		}

		webSocketClient = vertx.createWebSocketClient();

		healthLogTimerId = vertx.setPeriodic(HEALTH_LOG_INTERVAL_MS, id -> logPeriodicHealth());
		heartbeatTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL_MS, id -> sendHeartbeat());
		statusTimerId = vertx.setPeriodic(STATUS_INTERVAL_MS, id -> sendStatusUpdate());

		connectNow();
	}

	/**
	 * Shut down without abandoning work.
	 *
	 * <p>A worker that simply exits leaves every task it held {@code RUNNING} until the
	 * lease lapses, and the lease is deliberately generous - so scaling a fleet down
	 * costs the affected runs a lease interval per outstanding task before any of it is
	 * placed again. This closes that gap in three steps:</p>
	 *
	 * <ol>
	 * <li>announce {@code TERMINATING}, which is what stops Loom placing anything more
	 * here (see {@code ProcessorRegistry#isPlaceable});</li>
	 * <li>stop accepting tasks locally, because a dispatch already on the wire arrives
	 * after that announcement and would otherwise be started seconds before exit;</li>
	 * <li>wait out the grace period, then hand back whatever is still running so Loom
	 * can re-place it at once.</li>
	 * </ol>
	 *
	 * <p>Blocking, and called from the shutdown path rather than from the event loop.
	 * The connection is left open throughout - the hand-back needs it - and is closed by
	 * {@link #stop()} afterwards.</p>
	 *
	 * @param graceMs how long to let running tasks finish before they are handed back
	 */
	public void drain(long graceMs) {
		if (!started.get() || !connected.get() || websocket == null) {
			// Never connected, or already down. There is nobody to tell and nothing that
			// could have been dispatched here.
			return;
		}

		sendMessage(new ProcessorMessage(ProcessorMessageType.STATE_CHANGE,
			new JsonObject().put("state", "TERMINATING")));

		int running = taskHandler.beginDrain();
		log.info("Draining: announced TERMINATING with {} task(s) in flight, waiting up to {} ms", running, graceMs);

		int remaining = taskHandler.awaitDrain(graceMs);
		if (remaining == 0) {
			log.info("Drain complete: every in-flight task finished");
		} else {
			log.warn("Drain deadline reached with {} task(s) still running; handing them back to Loom", remaining);
		}
		// Called unconditionally: a task that finished between the wait ending and this
		// call has already left the set, and returning nothing is a no-op.
		int returned = taskHandler.returnOutstanding("worker '" + nodeId + "' is shutting down");
		if (returned > 0) {
			log.info("Returned {} unfinished task(s) for immediate re-placement", returned);
		}
		// stop() closes the socket next, and a queued frame is lost when it does.
		awaitFlush(FLUSH_TIMEOUT_MS);
	}

	public void stop() {
		if (!started.compareAndSet(true, false)) {
			return;
		}

		cancelTimer(heartbeatTimerId);
		cancelTimer(statusTimerId);
		cancelTimer(healthLogTimerId);
		cancelTimer(reconnectTimerId);
		heartbeatTimerId = -1;
		statusTimerId = -1;
		healthLogTimerId = -1;
		reconnectTimerId = -1;

		registered.set(false);
		connected.set(false);

		if (websocket != null) {
			try {
				websocket.close();
			} catch (Exception e) {
				log.debug("Ignoring websocket close error", e);
			}
			websocket = null;
		}
		if (webSocketClient != null) {
			try {
				webSocketClient.close();
			} catch (Exception e) {
				log.debug("Ignoring websocket client close error", e);
			}
			webSocketClient = null;
		}
	}

	/**
	 * Register the connection-state and resource gauges once. Gauges poll live state at scrape time,
	 * so they reflect the current values even while the channel is offline. Micrometer treats a
	 * re-registration of the same name as a no-op.
	 */
	private void registerGauges() {
		metrics.bindGauge("cortex_loom_connected", () -> connected.get() ? 1 : 0);
		metrics.bindGauge("cortex_loom_registered", () -> registered.get() ? 1 : 0);
		metrics.bindGauge("cortex_loom_reconnect_attempts", reconnectAttempts::get);
		metrics.bindGauge("cortex_memory_used_bytes", () -> Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
		metrics.bindGauge("cortex_memory_max_bytes", () -> Runtime.getRuntime().maxMemory());
		// Both are the same percentages reported in STATUS_UPDATE, so what an operator
		// sees on a dashboard is what Loom placed work on. Unknown scrapes as 0 because a
		// gauge has no way to say "unknown"; the status message keeps the distinction.
		metrics.bindGauge("cortex_cpu_load", () -> {
			Double load = loadProbe.cpuLoad();
			return load == null ? 0d : load;
		});
		metrics.bindGauge("cortex_io_load", () -> {
			Double load = loadProbe.ioLoad();
			return load == null ? 0d : load;
		});
		metrics.bindGauge("cortex_disk_used_bytes", () -> diskSpace(true));
		metrics.bindGauge("cortex_disk_total_bytes", () -> diskSpace(false));
	}

	private long diskSpace(boolean used) {
		try {
			FileStore store = Files.getFileStore(Path.of("."));
			return used ? store.getTotalSpace() - store.getUsableSpace() : store.getTotalSpace();
		} catch (Exception e) {
			return 0L;
		}
	}

	public boolean isReady() {
		return endpointConfigured && connected.get() && registered.get();
	}

	public JsonObject healthStatus() {
		return new JsonObject()
			.put("configured", endpointConfigured)
			.put("connected", connected.get())
			.put("registered", registered.get())
			.put("host", resolvedHost)
			.put("port", resolvedPort)
			.put("reconnectAttempts", reconnectAttempts.get())
			.put("lastConnectedAt", nullIfZero(lastConnectedAt))
			.put("lastMessageAt", nullIfZero(lastMessageAt))
			.put("lastHeartbeatAckAt", nullIfZero(lastHeartbeatAckAt))
			.put("error", connectionError);
	}

	private Long nullIfZero(long value) {
		return value == 0 ? null : value;
	}

	private void resolveEndpoint() {
		LoomClientOptions loom = options.getLoom();
		String host = loom != null ? loom.getHostname() : null;
		int port = loom != null ? loom.getPort() : 0;
		if (host == null || host.isBlank()) {
			endpointConfigured = false;
			connectionError = "LOOM host not configured";
			return;
		}
		if (port <= 0) {
			endpointConfigured = false;
			connectionError = "LOOM port not configured";
			return;
		}
		resolvedHost = host;
		resolvedPort = port;
		resolvedToken = resolveToken(loom);
		endpointConfigured = true;
		connectionError = null;
	}

	private static String resolveToken(LoomClientOptions loom) {
		if (loom != null && loom.getToken() != null && !loom.getToken().isBlank()) {
			return loom.getToken();
		}
		String env = System.getenv("LOOM_TOKEN");
		if (env != null && !env.isBlank()) {
			return env;
		}
		return null;
	}

	private void connectNow() {
		if (!started.get() || !endpointConfigured || webSocketClient == null) {
			return;
		}

		String uri = PROCESSOR_WS_URI;
		if (resolvedToken != null && !resolvedToken.isBlank()) {
			uri = uri + "?token=" + java.net.URLEncoder.encode(resolvedToken,
				java.nio.charset.StandardCharsets.UTF_8);
		}
		WebSocketConnectOptions connectOptions = new WebSocketConnectOptions()
			.setHost(resolvedHost)
			.setPort(resolvedPort)
			.setURI(uri);

		webSocketClient.connect(connectOptions)
			.onSuccess(this::onConnected)
			.onFailure(err -> {
				connected.set(false);
				registered.set(false);
				connectionError = err.getMessage();
				scheduleReconnect();
			});
	}

	private void onConnected(WebSocket ws) {
		websocket = ws;
		connected.set(true);
		registered.set(false);
		lastConnectedAt = System.currentTimeMillis();
		lastMessageAt = lastConnectedAt;
		connectionError = null;

		ws.textMessageHandler(this::handleIncomingMessage);
		ws.exceptionHandler(err -> {
			connectionError = err.getMessage();
			log.error("Loom control websocket error", err);
		});
		ws.closeHandler(v -> {
			connected.set(false);
			registered.set(false);
			websocket = null;
			if (started.get()) {
				scheduleReconnect();
			}
		});

		sendRegister();
		log.info("Connected to Loom control websocket {}:{}{}", resolvedHost, resolvedPort, PROCESSOR_WS_URI);
	}

	private void scheduleReconnect() {
		if (!started.get()) {
			return;
		}
		if (reconnectTimerId != -1) {
			return;
		}
		long attempt = reconnectAttempts.incrementAndGet();
		metrics.recordReconnect();
		long delay = Math.min(RECONNECT_BASE_DELAY_MS * Math.max(1, attempt), RECONNECT_MAX_DELAY_MS);
		reconnectTimerId = vertx.setTimer(delay, id -> {
			reconnectTimerId = -1;
			connectNow();
		});
		log.warn("Loom control websocket disconnected. Reconnecting in {} ms (attempt {})", delay, attempt);
	}

	private void sendRegister() {
		ProcessorRegistration registration = new ProcessorRegistration()
			.setNodeId(nodeId)
			.setName("cortex")
			.setPriority(100)
			.setHost(resolveSelfHost())
			.setCapabilities(EnumSet.of(ProcessorCapability.CPU, ProcessorCapability.IO))
			// Declaring what this worker will run is what lets Loom keep a heterogeneous
			// pool: a null or empty whitelist means "anything", so an unconfigured worker
			// keeps receiving everything rather than dropping out. The blacklist narrows
			// that by refusing specific kinds even when the whitelist would admit them.
			.setNodeWhitelist(announcedNodeWhitelist())
			.setNodeBlacklist(options.getNodeBlacklist());

		sendMessage(new ProcessorMessage(ProcessorMessageType.REGISTER, JsonObject.mapFrom(registration)));
	}

	/**
	 * Tell Loom what this worker's nodes look like, so they can be authored in the pipeline editor.
	 *
	 * <p>
	 * Sent <em>after</em> {@code REGISTERED} rather than as part of {@code REGISTER}: registration is a
	 * cheap synchronous in-memory operation on Loom's side and has to stay that way, while ingesting
	 * these contracts writes to Postgres. Putting both on one path would turn a reconnect storm into a
	 * database problem. The window where this worker is online but its contracts are not yet ingested
	 * is harmless, because dispatch reads the whitelist and never the descriptor registry.
	 * </p>
	 *
	 * <p>
	 * Announcing is not what makes a node runnable — {@link #announcedNodeWhitelist()} still does that.
	 * A worker whose announcement is refused keeps executing exactly the same work; it just does not
	 * contribute a palette entry.
	 * </p>
	 */
	private void sendNodeRegistration() {
		if (!options.isNodeSpecAnnounceEnabled()) {
			log.info("Node spec announcement is disabled; this worker's custom nodes will not appear in the editor");
			return;
		}
		List<NodeDescriptor> descriptors;
		try {
			// Intersecting with what this worker may run is what keeps the announced set and the
			// runnable set from drifting - and it is also the mitigation for the class-loading hazard,
			// since only these nodes get their port constants read.
			descriptors = NodeSpecCatalog.harvestRunnable(runnableNodeIds(), getClass().getClassLoader());
		} catch (RuntimeException | LinkageError e) {
			log.warn("Could not harvest node contracts; this worker will run normally but announce nothing", e);
			return;
		}
		if (descriptors.isEmpty()) {
			log.debug("No node contracts to announce");
			return;
		}
		NodeRegistration registration = new NodeRegistration(nodeId, descriptors);
		sendMessage(new ProcessorMessage(ProcessorMessageType.NODE_REGISTRATION, JsonObject.mapFrom(registration)));
		log.info("Announced {} node contracts to Loom", descriptors.size());
	}

	/**
	 * The node types this worker may actually execute, after its whitelist and blacklist.
	 *
	 * <p>
	 * {@link #announcedNodeWhitelist()} returns null for "unrestricted", which is right on the
	 * registration frame and useless here — an announcement needs the concrete set.
	 * </p>
	 */
	private java.util.Set<String> runnableNodeIds() {
		java.util.Set<String> runnable;
		try {
			runnable = new java.util.LinkedHashSet<>(nodeFactory.get().registeredTypes());
		} catch (Exception e) {
			log.warn("Could not determine executable node types; announcing nothing", e);
			return java.util.Set.of();
		}
		java.util.Set<String> whitelist = options.getNodeWhitelist();
		if (whitelist != null && !whitelist.isEmpty()) {
			runnable.retainAll(whitelist);
		}
		java.util.Set<String> blacklist = options.getNodeBlacklist();
		if (blacklist != null) {
			runnable.removeAll(blacklist);
		}
		return runnable;
	}

	/**
	 * Report what Loom made of the announcement.
	 *
	 * <p>
	 * Every rejection is logged by name and reason. A silent ack is how an author ends up editing a
	 * forked node's ports, seeing no effect in the editor, and losing an afternoon to it — and it is
	 * also what every custom-node author would copy from the example worker.
	 * </p>
	 */
	private void handleNodeRegistrationAck(ProcessorMessage message) {
		if (message.getBody() == null) {
			return;
		}
		NodeRegistrationAck ack = message.getBody().mapTo(NodeRegistrationAck.class);
		log.info("Loom accepted {} node contracts", ack.getAccepted().size());
		for (NodeRegistrationRejection rejection : ack.getRejected()) {
			if (rejection.getReason() == NodeRegistrationRejection.Reason.BUILTIN) {
				// Routine: Loom ships its own contract for this node and it wins. Worth saying once,
				// because it is the answer to "why did my edit have no effect".
				log.info("Node '{}': {}", rejection.getNodeId(), rejection.getMessage());
			} else {
				log.warn("Node '{}' was not adopted ({}): {}", rejection.getNodeId(), rejection.getReason(),
					rejection.getMessage());
			}
		}
	}

	/**
	 * What this worker tells Loom it can run.
	 *
	 * <p>Defaults to what the node factory actually has, so a worker cannot advertise
	 * work it is unable to perform - the two would otherwise drift, and the failure
	 * looks like a task dispatched to a worker that then cannot run it. An explicit
	 * setting narrows that further, for dedicating a machine to part of a pipeline.</p>
	 */
	private java.util.Set<String> announcedNodeWhitelist() {
		java.util.Set<String> configured = options.getNodeWhitelist();
		if (configured != null && !configured.isEmpty()) {
			return configured;
		}
		try {
			return nodeFactory.get().registeredTypes();
		} catch (Exception e) {
			// Announcing nothing means "accepts anything", which is the safe fallback.
			log.warn("Could not determine executable node kinds; registering as unrestricted", e);
			return null;
		}
	}

	private String resolveSelfHost() {
		try {
			return InetAddress.getLocalHost().getHostName() + ":" + options.getMonitoringPort();
		} catch (Exception e) {
			return "unknown:" + options.getMonitoringPort();
		}
	}

	private void sendHeartbeat() {
		if (!connected.get() || websocket == null) {
			return;
		}
		sendMessage(new ProcessorMessage(ProcessorMessageType.HEARTBEAT));
	}

	private void sendStatusUpdate() {
		if (!connected.get() || websocket == null || !registered.get()) {
			return;
		}
		SystemStatusInfo info = collectSystemStatus();
		sendMessage(new ProcessorMessage(ProcessorMessageType.STATUS_UPDATE, JsonObject.mapFrom(info)));
	}

	private SystemStatusInfo collectSystemStatus() {
		Runtime runtime = Runtime.getRuntime();
		long usedMemory = runtime.totalMemory() - runtime.freeMemory();
		long totalMemory = runtime.maxMemory();

		Long diskTotal = null;
		Long diskUsed = null;
		try {
			FileStore store = Files.getFileStore(Path.of("."));
			diskTotal = store.getTotalSpace();
			diskUsed = store.getTotalSpace() - store.getUsableSpace();
		} catch (Exception e) {
			log.debug("Unable to collect disk metrics", e);
		}

		return new SystemStatusInfo()
			.setCpuLoad(loadProbe.cpuLoad())
			.setIoLoad(loadProbe.ioLoad())
			.setMemoryUsed(usedMemory)
			.setMemoryTotal(totalMemory)
			.setDiskTotal(diskTotal)
			.setDiskUsed(diskUsed);
	}

	private void handleIncomingMessage(String payload) {
		lastMessageAt = System.currentTimeMillis();
		ProcessorMessage message;
		try {
			message = Json.decodeValue(payload, ProcessorMessage.class);
		} catch (Exception e) {
			log.warn("Ignoring malformed processor message: {}", payload, e);
			return;
		}

		if (message.getType() == null) {
			return;
		}

		metrics.recordLoomMessage(message.getType().name().toLowerCase());

		switch (message.getType()) {
			case REGISTERED:
				registered.set(true);
				reconnectAttempts.set(0);
				// Only now, and on every reconnect: Loom drops the ANNOUNCED links for a worker it has
				// not heard from, and re-announcing an unchanged set is cheap and silent on its side.
				sendNodeRegistration();
				break;
			case NODE_REGISTRATION_ACK:
				handleNodeRegistrationAck(message);
				break;
			case HEARTBEAT_ACK:
				lastHeartbeatAckAt = System.currentTimeMillis();
				break;
			case NODE_TASK:
				handleNodeTask(message);
				break;
			case SEGMENT_TASK:
				handleSegmentTask(message);
				break;
			case SOURCE_TASK:
				handleSourceTask(message);
				break;
			case SOURCE_ITEMS_ACK:
				handleSourceItemsAck(message);
				break;
			case ERROR:
				connectionError = message.getBody() != null ? message.getBody().getString("message") : "unknown";
				log.warn("Loom reported websocket error: {}", connectionError);
				break;
			default:
				log.debug("Ignoring processor websocket message type {}", message.getType());
				break;
		}
	}

	/**
	 * Execute one node against one media item and answer with the outcome.
	 *
	 * <p>Handing off to {@link PipelineTaskHandler} keeps the work off this
	 * connection's thread - a transcription task would otherwise stall heartbeats
	 * and every other message for minutes.</p>
	 */
	private void handleNodeTask(ProcessorMessage message) {
		if (message.getBody() == null) {
			log.warn("Ignoring NODE_TASK without body");
			return;
		}
		NodeTask task;
		try {
			task = message.getBody().mapTo(NodeTask.class);
		} catch (Exception e) {
			log.warn("Failed to parse NODE_TASK payload: {}", message.getBody(), e);
			return;
		}
		taskHandler.handleNodeTask(task, this::sendMessage);
	}

	/**
	 * Execute a whole affinity segment and answer with one result per node.
	 */
	private void handleSegmentTask(ProcessorMessage message) {
		if (message.getBody() == null) {
			log.warn("Ignoring SEGMENT_TASK without body");
			return;
		}
		SegmentTask task;
		try {
			task = message.getBody().mapTo(SegmentTask.class);
		} catch (Exception e) {
			log.warn("Failed to parse SEGMENT_TASK payload: {}", message.getBody(), e);
			return;
		}
		taskHandler.handleSegmentTask(task, this::sendMessage);
	}

	/**
	 * Run a source node and stream what it enumerates back in acknowledged batches.
	 */
	private void handleSourceTask(ProcessorMessage message) {
		if (message.getBody() == null) {
			log.warn("Ignoring SOURCE_TASK without body");
			return;
		}
		SourceTaskMessage task;
		try {
			task = message.getBody().mapTo(SourceTaskMessage.class);
		} catch (Exception e) {
			log.warn("Failed to parse SOURCE_TASK payload: {}", message.getBody(), e);
			return;
		}
		taskHandler.handleSourceTask(task, this::sendMessage);
	}

	/** Release the source runner to send its next batch. */
	private void handleSourceItemsAck(ProcessorMessage message) {
		if (message.getBody() == null) {
			log.warn("Ignoring SOURCE_ITEMS_ACK without body");
			return;
		}
		SourceItemsAckMessage ack;
		try {
			ack = message.getBody().mapTo(SourceItemsAckMessage.class);
		} catch (Exception e) {
			log.warn("Failed to parse SOURCE_ITEMS_ACK payload: {}", message.getBody(), e);
			return;
		}
		taskHandler.handleSourceItemsAck(ack.getRunUuid(), ack.getSeq());
	}

	/**
	 * Under Variant C a worker holds no pipeline graph, so it has nothing to report at
	 * pipeline-event granularity: Loom's {@code RunStatsAggregator} counts node settles
	 * and pushes {@code NODE_STATS} on a timer, releasing only failures immediately.
	 * Forwarding this worker's tracking bus straight to Loom went past that aggregation
	 * and is gone. Nothing publishes to the tracking bus outside the node-chain tests.
	 */
	private void sendMessage(ProcessorMessage message) {
		write(message);
	}

	/**
	 * @return when the frame has been written, or a failed future when there is no
	 *         connection to write it to
	 */
	private io.vertx.core.Future<Void> write(ProcessorMessage message) {
		if (websocket == null) {
			return io.vertx.core.Future.failedFuture("no websocket");
		}
		return websocket.writeTextMessage(Json.encode(message))
			.onFailure(err -> {
				connectionError = err.getMessage();
				log.warn("Failed to send websocket message type {}", message.getType(), err);
			});
	}

	/**
	 * Block until everything already handed to the socket has been written.
	 *
	 * <p>Writes are queued on the event loop, so a shutdown that closes the connection
	 * straight after sending would discard the very hand-backs the drain exists to
	 * deliver. Frames on one WebSocket are written in order, so waiting on one more
	 * write waits for all of them.</p>
	 *
	 * @param timeoutMs how long to wait before giving up on the flush
	 */
	private void awaitFlush(long timeoutMs) {
		try {
			write(new ProcessorMessage(ProcessorMessageType.HEARTBEAT))
				.toCompletionStage().toCompletableFuture()
				.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			log.warn("Could not confirm that pending messages were flushed before disconnecting", e);
		}
	}

	private void logPeriodicHealth() {
		JsonObject status = healthStatus();
		log.info("Loom control health: {}", status.encode());
	}

	private void cancelTimer(long timerId) {
		if (timerId != -1) {
			vertx.cancelTimer(timerId);
		}
	}

}
