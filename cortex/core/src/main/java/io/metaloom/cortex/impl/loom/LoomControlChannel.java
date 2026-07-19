package io.metaloom.cortex.impl.loom;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import io.metaloom.cortex.pipeline.api.event.PipelineEventBus;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.SystemStatusInfo;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.SourceItemsAckMessage;
import io.metaloom.loom.rest.model.processor.message.SourceTaskMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
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

	private final Vertx vertx;
	private final CortexOptions options;
	private final PipelineEventBus pipelineEventBus;
	private final PipelineWorkOrderHandler workOrderHandler;

	private final PipelineTaskHandler taskHandler;

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
	private String trackingSubscriptionId;

	private volatile long lastConnectedAt;
	private volatile long lastMessageAt;
	private volatile long lastHeartbeatAckAt;
	private volatile String connectionError;
	private volatile String resolvedHost;
	private volatile int resolvedPort;
	private volatile String resolvedToken;
	private volatile boolean endpointConfigured;

	@Inject
	public LoomControlChannel(Vertx vertx, CortexOptions options, PipelineEventBus pipelineEventBus,
			PipelineWorkOrderHandler workOrderHandler, PipelineTaskHandler taskHandler,
			dagger.Lazy<io.metaloom.cortex.pipeline.loader.NodeFactory> nodeFactory) {
		this.nodeFactory = nodeFactory;
		this.vertx = vertx;
		this.options = options;
		// A configured id survives a restart; a generated one does not, and Loom keys
		// leases and attribution on it.
		this.nodeId = options.getNodeId() != null && !options.getNodeId().isBlank()
			? options.getNodeId()
			: "cortex-" + UUID.randomUUID();
		this.pipelineEventBus = pipelineEventBus;
		this.workOrderHandler = workOrderHandler;
		this.taskHandler = taskHandler;
	}

	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}

		resolveEndpoint();
		if (!endpointConfigured) {
			log.warn("No Loom websocket endpoint configured. Control channel disabled.");
			return;
		}

		webSocketClient = vertx.createWebSocketClient();
		trackingSubscriptionId = pipelineEventBus.subscribeTracking(this::forwardPipelineTrackingEvent);

		healthLogTimerId = vertx.setPeriodic(HEALTH_LOG_INTERVAL_MS, id -> logPeriodicHealth());
		heartbeatTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL_MS, id -> sendHeartbeat());
		statusTimerId = vertx.setPeriodic(STATUS_INTERVAL_MS, id -> sendStatusUpdate());

		connectNow();
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

		if (trackingSubscriptionId != null) {
			pipelineEventBus.unsubscribe(trackingSubscriptionId);
			trackingSubscriptionId = null;
		}

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
			// pool: a null or empty set means "anything", so an unconfigured worker keeps
			// receiving everything rather than dropping out.
			.setNodeKinds(announcedNodeKinds());

		sendMessage(new ProcessorMessage(ProcessorMessageType.REGISTER, JsonObject.mapFrom(registration)));
	}

	/**
	 * What this worker tells Loom it can run.
	 *
	 * <p>Defaults to what the node factory actually has, so a worker cannot advertise
	 * work it is unable to perform - the two would otherwise drift, and the failure
	 * looks like a task dispatched to a worker that then cannot run it. An explicit
	 * setting narrows that further, for dedicating a machine to part of a pipeline.</p>
	 */
	private java.util.Set<String> announcedNodeKinds() {
		java.util.Set<String> configured = options.getNodeKinds();
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

		double loadAverage = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
		Double cpuLoad = loadAverage < 0 ? null : Math.min(100.0d, Math.max(0.0d, loadAverage * 100.0d));

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
			.setCpuLoad(cpuLoad)
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

		switch (message.getType()) {
			case REGISTERED:
				registered.set(true);
				reconnectAttempts.set(0);
				break;
			case HEARTBEAT_ACK:
				lastHeartbeatAckAt = System.currentTimeMillis();
				break;
			case WORK_ORDER:
				handleWorkOrder(message);
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

	private void handleWorkOrder(ProcessorMessage message) {
		if (message.getBody() == null) {
			log.warn("Ignoring WORK_ORDER without body");
			return;
		}
		WorkOrder workOrder;
		try {
			workOrder = message.getBody().mapTo(WorkOrder.class);
		} catch (Exception e) {
			log.warn("Failed to parse WORK_ORDER payload: {}", message.getBody(), e);
			return;
		}

		WorkOrderResult result = workOrderHandler.handle(workOrder);
		sendMessage(new ProcessorMessage(ProcessorMessageType.WORK_ORDER_RESULT, JsonObject.mapFrom(result)));
	}

	private void forwardPipelineTrackingEvent(PipelineTrackingEvent event) {
		if (!connected.get() || websocket == null || !registered.get()) {
			return;
		}
		PipelineEventMessage outgoing = new PipelineEventMessage()
			.setType(PipelineEventType.valueOf(event.getType().name()))
			.setPipelineName(event.getPipelineName())
			.setPipelineRunUuid(event.getPipelineRunUuid())
			.setNodeId(event.getNodeId())
			.setMediaPath(event.getMediaPath())
			.setTimestamp(event.getTimestamp())
			.setDurationMs(event.getDurationMs())
			.setMessage(event.getMessage());
		sendMessage(new ProcessorMessage(ProcessorMessageType.PIPELINE_EVENT, JsonObject.mapFrom(outgoing)));

		// If this is a pipeline completion event, also send a PIPELINE_RUN_COMPLETED
		// message carrying the run correlation id and the per-media aggregate
		// counters, so Loom can close out the pipeline_run record.
		if (event.getType() == PipelineTrackingEvent.Type.PIPELINE_COMPLETED) {
			JsonObject completionPayload = new JsonObject()
				.put("pipelineName", event.getPipelineName())
				.put("pipelineRunUuid", event.getPipelineRunUuid())
				.put("timestamp", event.getTimestamp())
				.put("durationMs", event.getDurationMs())
				.put("message", event.getMessage());
			PipelineTrackingEvent.RunCounters counters = event.getCounters();
			if (counters != null) {
				completionPayload
					.put("mediaCount", counters.getMediaCount())
					.put("successCount", counters.getSuccessCount())
					.put("failureCount", counters.getFailureCount())
					.put("skippedCount", counters.getSkippedCount());
			}
			sendMessage(new ProcessorMessage(ProcessorMessageType.PIPELINE_RUN_COMPLETED, completionPayload));
		}
	}

	private void sendMessage(ProcessorMessage message) {
		if (websocket == null) {
			return;
		}
		websocket.writeTextMessage(Json.encode(message))
			.onFailure(err -> {
				connectionError = err.getMessage();
				log.warn("Failed to send websocket message type {}", message.getType(), err);
			});
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
