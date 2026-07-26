package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PUT;

import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.model.processor.ProcessorRestrictionUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.processor.ProcessorListResponse;
import io.metaloom.loom.rest.model.processor.ProcessorResponse;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.SystemStatusInfo;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.rest.model.processor.message.NodeTaskResultMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.SourceCompleteMessage;
import io.metaloom.loom.rest.model.processor.message.SourceItemsAckMessage;
import io.metaloom.loom.rest.model.processor.message.SourceItemsMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.PipelineRunRegistry;
import io.metaloom.loom.rest.service.impl.PipelineRunTracker;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;
import io.metaloom.loom.rest.service.impl.WebSocketAuthenticator;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

/**
 * Endpoint that manages WebSocket connections from processor (cortex) nodes.
 * 
 * <p>Processor nodes connect via WebSocket at {@code /api/v1/processors/ws} and exchange
 * JSON messages using the {@link ProcessorMessage} envelope format. The endpoint also
 * exposes REST routes for listing and inspecting registered processors.</p>
 * 
 * <h1>WebSocket Protocol</h1>
 * <ol>
 *   <li>Processor connects and sends a {@code REGISTER} message with its capabilities</li>
 *   <li>Loom responds with {@code REGISTERED} acknowledgement</li>
 *   <li>Processor sends periodic {@code HEARTBEAT} messages; loom replies with {@code HEARTBEAT_ACK}</li>
 *   <li>Processor sends {@code STATUS_UPDATE} with system metrics</li>
 *   <li>Loom sends {@code NODE_TASK} / {@code SOURCE_TASK} / {@code SEGMENT_TASK} messages to dispatch work</li>
 *   <li>Processor streams back {@code SOURCE_ITEMS} and reports {@code NODE_TASK_RESULT} / {@code SEGMENT_TASK_RESULT}</li>
 * </ol>
 */
public class ProcessorEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ProcessorEndpoint.class);

	private final ProcessorRegistry registry;
	private final PipelineEventBroadcaster pipelineEventBroadcaster;
	private final WebSocketAuthenticator authenticator;
	private final PipelineRunTracker pipelineRunTracker;

	private final PipelineRunRegistry pipelineRunRegistry;
	private final ModelExamples examples;

	private final io.metaloom.loom.common.metrics.LoomMetrics metrics;

	@Inject
	public ProcessorEndpoint(ProcessorRegistry registry, PipelineEventBroadcaster pipelineEventBroadcaster,
			WebSocketAuthenticator authenticator,
			PipelineRunTracker pipelineRunTracker, PipelineRunRegistry pipelineRunRegistry,
			EndpointDependencies deps, ModelExamples examples, io.metaloom.loom.common.metrics.LoomMetrics metrics) {
		super(deps);
		this.registry = registry;
		this.pipelineRunRegistry = pipelineRunRegistry;
		this.pipelineEventBroadcaster = pipelineEventBroadcaster;
		this.authenticator = authenticator;
		this.pipelineRunTracker = pipelineRunTracker;
		this.examples = examples;
		this.metrics = metrics;
	}

	@Override
	public String name() {
		return "processor";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/processors";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// WebSocket upgrade route — NOT secured via the standard auth handler because
		// the WS upgrade happens before the handler chain can authenticate. Instead
		// we accept a ?token=<jwt> query parameter and validate it via
		// WebSocketAuthenticator once the socket is open. The processor still sends
		// a REGISTER message for identity/capability metadata.
		apiRouter().getDelegate().get(basePath() + "/ws").handler(rc -> {
			rc.request().toWebSocket()
				.onSuccess(ws -> authenticator.authenticate(ws, "processor")
					.onSuccess(v -> handleWebSocket(ws))
					.onFailure(err -> log.debug("Processor WebSocket rejected: {}", err.getMessage())))
				.onFailure(err -> {
					log.warn("Processor WebSocket upgrade failed", err);
					rc.response().setStatusCode(400).end("WebSocket upgrade failed");
				});
		});

		secure(basePath());
		secure(basePath() + "/:uuid");
		secure(basePath() + "/:uuid/restrictions");

		// List all registered processors, merged with persisted-but-offline instances so an
		// operator can still see and edit a remembered worker's restrictions while it is down.
		addListRoute(basePath(), GET,
			"Load a list of registered processor nodes",
			examples.processorListResponseExample(),
			lrc -> {
				ProcessorListResponse list = new ProcessorListResponse();
				for (ProcessorResponse resp : registry.listAllResponses()) {
					list.add(resp);
				}
				lrc.send(list, 200);
			});

		// Read a single processor
		addRoute(basePath() + "/:uuid", GET,
			"Load a registered processor node",
			null,
			examples.processorResponseExample(),
			lrc -> {
				String uuid = lrc.pathParam("uuid");
				ConnectedProcessor processor = registry.get(uuid);
				if (processor == null) {
					// Try to find by derived UUID among live processors
					for (ConnectedProcessor p : registry.getAll()) {
						ProcessorResponse resp = registry.toResponse(p);
						if (resp.getUuid().toString().equals(uuid)) {
							lrc.send(registry.toResponse(p), 200);
							return;
						}
					}
					// Fall back to a persisted-but-offline instance keyed by nodeId
					ProcessorResponse persisted = registry.loadPersisted(uuid);
					if (persisted != null) {
						lrc.send(persisted, 200);
						return;
					}
					lrc.sendText("{\"message\":\"Processor not found\"}", "application/json", 404);
					return;
				}
				lrc.send(registry.toResponse(processor), 200);
			});

		// Update the administrator-managed node-kind restrictions of a cortex instance.
		addRoute(basePath() + "/:uuid/restrictions", PUT,
			"Update the node-kind restrictions (whitelist/blacklist) of a cortex instance",
			null,
			examples.processorResponseExample(),
			lrc -> lrc.requirePerm(Permission.MANAGE_CORTEX_INSTANCE)
				.onSuccess(l -> {
					String nodeId = lrc.pathParam("uuid");
					ProcessorRestrictionUpdateRequest req = lrc.requestBody(ProcessorRestrictionUpdateRequest.class);
					ProcessorResponse resp = registry.updateRestrictions(nodeId, req.getNodeWhitelist(), req.getNodeBlacklist(), lrc.userUuid());
					lrc.send(resp, 200);
				})
				.onFailure(e -> {
					throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
				}));

		// Forget a persisted (offline) cortex instance.
		addRoute(basePath() + "/:uuid", DELETE,
			"Forget a persisted cortex instance. Only permitted while the worker is offline.",
			lrc -> lrc.requirePerm(Permission.MANAGE_CORTEX_INSTANCE)
				.onSuccess(l -> {
					String nodeId = lrc.pathParam("uuid");
					if (registry.isConnected(nodeId)) {
						lrc.sendText("{\"message\":\"Worker is online; disconnect before forgetting\"}", "application/json", 409);
						return;
					}
					if (!registry.forget(nodeId)) {
						lrc.sendText("{\"message\":\"Processor not found\"}", "application/json", 404);
						return;
					}
					lrc.sendNoContent();
				})
				.onFailure(e -> {
					throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
				}));
	}

	/**
	 * Handle a new WebSocket connection from a processor node.
	 */
	private void handleWebSocket(ServerWebSocket ws) {
		// The nodeId will be set upon REGISTER
		final String[] nodeIdHolder = { null };

		ws.textMessageHandler(text -> {
			ProcessorMessage msg;
			try {
				msg = Json.decodeValue(text, ProcessorMessage.class);
			} catch (Exception e) {
				log.warn("Invalid processor message: {}", e.getMessage());
				sendError(ws, "Invalid message format");
				return;
			}

			if (msg.getType() == null) {
				sendError(ws, "Missing message type");
				return;
			}

			switch (msg.getType()) {
				case REGISTER:
					handleRegister(ws, msg, nodeIdHolder);
					break;
				case HEARTBEAT:
					handleHeartbeat(ws, nodeIdHolder[0]);
					break;
				case STATUS_UPDATE:
					handleStatusUpdate(ws, msg, nodeIdHolder[0]);
					break;
				case STATE_CHANGE:
					handleStateChange(ws, msg, nodeIdHolder[0]);
					break;
				case PIPELINE_EVENT:
					handlePipelineEvent(ws, msg, nodeIdHolder[0]);
					break;
				case PIPELINE_RUN_COMPLETED:
					handlePipelineRunCompleted(ws, msg, nodeIdHolder[0]);
					break;
				case SOURCE_ITEMS:
					handleSourceItems(ws, msg, nodeIdHolder[0]);
					break;
				case SOURCE_COMPLETE:
					handleSourceComplete(ws, msg, nodeIdHolder[0]);
					break;
				case SEGMENT_TASK_RESULT:
					handleSegmentTaskResult(ws, msg, nodeIdHolder[0]);
					break;
				case NODE_TASK_RESULT_BATCH:
					handleNodeTaskResultBatch(ws, msg, nodeIdHolder[0]);
					break;
				case NODE_TASK_RESULT:
					handleNodeTaskResult(ws, msg, nodeIdHolder[0]);
					break;
				default:
					sendError(ws, "Unexpected message type: " + msg.getType());
					break;
			}
		});

		ws.closeHandler(v -> {
			if (nodeIdHolder[0] != null) {
				log.info("Processor disconnected: {}", nodeIdHolder[0]);
				// Scope the OFFLINE transition + removal to this socket so a late close of a
				// connection already superseded by a reconnect does not evict the live worker.
				registry.disconnect(nodeIdHolder[0], ws);
			}
		});

		ws.exceptionHandler(err -> {
			log.error("Processor WebSocket error for node {}", nodeIdHolder[0], err);
		});
	}

	private void handleRegister(ServerWebSocket ws, ProcessorMessage msg, String[] nodeIdHolder) {
		if (msg.getBody() == null) {
			sendError(ws, "REGISTER message must include a body");
			return;
		}
		ProcessorRegistration reg = msg.getBody().mapTo(ProcessorRegistration.class);
		if (reg.getNodeId() == null || reg.getNodeId().isBlank()) {
			sendError(ws, "REGISTER message must include a nodeId");
			return;
		}
		// A node id identifies exactly one live worker. If another worker is already connected
		// under this id, reject rather than silently replacing it - two workers sharing an id
		// would fight over one cortex_instance row and its admin-managed restrictions, and work
		// dispatched to the id could land on either. A stale entry whose socket has already
		// closed is not "live" and is allowed to be taken over (a normal reconnect).
		if (registry.isLive(reg.getNodeId())) {
			log.warn("Rejecting REGISTER for nodeId '{}': a worker with this id is already connected", reg.getNodeId());
			sendError(ws, "A processor with nodeId '" + reg.getNodeId() + "' is already connected. "
				+ "Each worker must register under a unique, stable node id.");
			ws.close((short) 4409, "Duplicate nodeId");
			return;
		}
		nodeIdHolder[0] = reg.getNodeId();
		registry.register(reg.getNodeId(), reg, ws);

		// Send acknowledgement
		ProcessorMessage ack = new ProcessorMessage(ProcessorMessageType.REGISTERED,
			JsonObject.mapFrom(registry.toResponse(registry.get(reg.getNodeId()))));
		ws.writeTextMessage(Json.encode(ack));
	}

	private void handleHeartbeat(ServerWebSocket ws, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		registry.heartbeat(nodeId);
		ProcessorMessage ack = new ProcessorMessage(ProcessorMessageType.HEARTBEAT_ACK);
		ws.writeTextMessage(Json.encode(ack));
	}

	private void handleStatusUpdate(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "STATUS_UPDATE message must include a body");
			return;
		}
		SystemStatusInfo status = msg.getBody().mapTo(SystemStatusInfo.class);
		registry.updateStatus(nodeId, status);
	}

	private void handleStateChange(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null || !msg.getBody().containsKey("state")) {
			sendError(ws, "STATE_CHANGE message must include a body with 'state' field");
			return;
		}
		ProcessorState state = ProcessorState.valueOf(msg.getBody().getString("state"));
		registry.updateState(nodeId, state);
	}

	private void handlePipelineEvent(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "PIPELINE_EVENT message must include a body");
			return;
		}
		PipelineEventMessage event = msg.getBody().mapTo(PipelineEventMessage.class);
		pipelineEventBroadcaster.broadcast(event);
	}

	/**
	 * Handle a batch of media items discovered by a source node.
	 *
	 * <p>Each batch is acknowledged so the processor may send the next. That ack is
	 * the only backpressure in the source path - without it a fast filesystem scan
	 * buries a slower engine.</p>
	 */
	private void handleSourceItems(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "SOURCE_ITEMS message must include a body");
			return;
		}
		SourceItemsMessage body = msg.getBody().mapTo(SourceItemsMessage.class);
		PipelineRunEngine engine = resolveEngine(ws, body.getRunUuid(), "SOURCE_ITEMS");
		if (engine == null) {
			return;
		}

		int count = 0;
		if (body.getItems() != null) {
			for (MediaRef item : body.getItems()) {
				engine.onItemDiscovered(item);
				count++;
			}
		}
		metrics.recordSourceItemsReceived(count);
		log.debug("Run {} received {} source item(s) in batch {}", body.getRunUuid(), count, body.getSeq());

		// The acknowledgement is the throttle. Holding it back while the run is at
		// capacity stops the scan itself, which is the only thing that bounds memory -
		// capping dispatch alone still lets a fast source pile up item state for media
		// nobody will process for hours.
		if (engine.isAtCapacity()) {
			log.debug("Run {} is at capacity; holding the ack for batch {}", body.getRunUuid(), body.getSeq());
		}
		engine.whenCapacityAvailable(() -> registry.send(nodeId, ProcessorMessageType.SOURCE_ITEMS_ACK,
			new SourceItemsAckMessage().setRunUuid(body.getRunUuid()).setSeq(body.getSeq())));
	}

	/**
	 * Handle the end of source enumeration. A run cannot complete before this arrives.
	 */
	private void handleSourceComplete(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "SOURCE_COMPLETE message must include a body");
			return;
		}
		SourceCompleteMessage body = msg.getBody().mapTo(SourceCompleteMessage.class);
		PipelineRunEngine engine = resolveEngine(ws, body.getRunUuid(), "SOURCE_COMPLETE");
		if (engine == null) {
			return;
		}
		if (body.getError() != null) {
			// Enumeration aborted. Close the source anyway so the run can settle on
			// whatever was already discovered rather than hanging forever.
			log.warn("Run {} source failed: {}", body.getRunUuid(), body.getError());
		}
		engine.onSourceComplete(body.getTotalCount());
	}

	/**
	 * Handle the outcome of a single node task and let the engine advance the item.
	 */
	/**
	 * Assimilate the per-node outcomes of a segment.
	 */
	private void handleSegmentTaskResult(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "SEGMENT_TASK_RESULT message must include a body");
			return;
		}
		io.metaloom.loom.pipeline.model.SegmentTaskResult body =
			msg.getBody().mapTo(io.metaloom.loom.pipeline.model.SegmentTaskResult.class);
		PipelineRunEngine engine = resolveEngine(ws, body.getRunUuid(), "SEGMENT_TASK_RESULT");
		if (engine == null) {
			return;
		}
		if (body.getItemId() == null) {
			sendError(ws, "SEGMENT_TASK_RESULT requires an itemId");
			return;
		}
		engine.onSegmentTaskResult(body.getItemId(), body.getSegmentId(), body.getResults(), body.getError());
	}

	/**
	 * Assimilate several results that travelled together.
	 *
	 * <p>Each entry goes through the ordinary single-result path, so batching stays a
	 * transport detail: retries, dead-lettering and downstream unblocking behave
	 * exactly as they would have one message at a time. One malformed entry is
	 * skipped rather than discarding the rest of the batch.</p>
	 */
	private void handleNodeTaskResultBatch(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "NODE_TASK_RESULT_BATCH message must include a body");
			return;
		}
		io.metaloom.loom.pipeline.model.NodeTaskResultBatch body =
			msg.getBody().mapTo(io.metaloom.loom.pipeline.model.NodeTaskResultBatch.class);
		PipelineRunEngine engine = resolveEngine(ws, body.getRunUuid(), "NODE_TASK_RESULT_BATCH");
		if (engine == null) {
			return;
		}
		for (io.metaloom.loom.pipeline.model.NodeTaskResultBatch.Entry entry : body.getEntries()) {
			try {
				engine.onNodeTaskResult(entry.getItemId(), entry.getResult());
				recordNodeResultReceived(entry.getResult());
			} catch (Exception e) {
				log.error("Failed to assimilate result for item '{}' in run {}", entry.getItemId(),
					body.getRunUuid(), e);
			}
		}
	}

	/** Record an inbound node result on the metrics catalog, keyed by node id and settled state. */
	private void recordNodeResultReceived(io.metaloom.loom.pipeline.model.NodeTaskResult result) {
		if (result == null) {
			return;
		}
		String kind = result.getNodeId() == null ? "unknown" : result.getNodeId();
		String state = result.getState() == null ? "unknown" : result.getState().name().toLowerCase();
		metrics.recordNodeResultReceived(kind, state);
	}

	private void handleNodeTaskResult(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "NODE_TASK_RESULT message must include a body");
			return;
		}
		NodeTaskResultMessage body = msg.getBody().mapTo(NodeTaskResultMessage.class);
		PipelineRunEngine engine = resolveEngine(ws, body.getRunUuid(), "NODE_TASK_RESULT");
		if (engine == null) {
			return;
		}
		if (body.getResult() == null || body.getItemId() == null) {
			sendError(ws, "NODE_TASK_RESULT requires both an itemId and a result");
			return;
		}
		engine.onNodeTaskResult(body.getItemId(), body.getResult());
		recordNodeResultReceived(body.getResult());
	}

	/**
	 * Resolve the engine for a run.
	 *
	 * <p>An unknown run is logged rather than treated as a protocol error: a late
	 * message for a run that already finished is normal, and a processor should not
	 * be disconnected for it.</p>
	 *
	 * @return the engine, or null when the run is unknown
	 */
	private PipelineRunEngine resolveEngine(ServerWebSocket ws, java.util.UUID runUuid, String messageType) {
		if (runUuid == null) {
			sendError(ws, messageType + " message must include a runUuid");
			return null;
		}
		PipelineRunEngine engine = pipelineRunRegistry.get(runUuid);
		if (engine == null) {
			log.debug("Ignoring {} for unknown or completed run {}", messageType, runUuid);
			return null;
		}
		return engine;
	}

	private void handlePipelineRunCompleted(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "PIPELINE_RUN_COMPLETED message must include a body");
			return;
		}
		JsonObject body = msg.getBody();
		String pipelineName = body.getString("pipelineName");
		String runUuidStr = body.getString("pipelineRunUuid");
		Long durationMs = body.getLong("durationMs");
		String message = body.getString("message");

		log.info("Pipeline run completed: pipeline={}, run={}, duration={}ms, message={}",
			pipelineName, runUuidStr, durationMs, message);

		// An untracked run (offline Cortex, CLI batch) reports completion without a
		// run id. There is nothing to persist — the event is still broadcast to UI
		// subscribers via the PIPELINE_EVENT that preceded this message.
		if (runUuidStr == null || runUuidStr.isBlank()) {
			log.debug("PIPELINE_RUN_COMPLETED for pipeline '{}' carried no pipelineRunUuid — nothing to persist",
				pipelineName);
			return;
		}

		UUID runUuid;
		try {
			runUuid = UUID.fromString(runUuidStr);
		} catch (IllegalArgumentException e) {
			log.warn("PIPELINE_RUN_COMPLETED carried a malformed pipelineRunUuid '{}'", runUuidStr);
			return;
		}

		int mediaCount = body.getInteger("mediaCount", 0);
		int successCount = body.getInteger("successCount", 0);
		int failureCount = body.getInteger("failureCount", 0);
		int skippedCount = body.getInteger("skippedCount", 0);

		pipelineRunTracker.complete(runUuid, durationMs, mediaCount, successCount, failureCount, skippedCount);
	}

	private void sendError(ServerWebSocket ws, String message) {
		ProcessorMessage error = new ProcessorMessage(ProcessorMessageType.ERROR,
			new JsonObject().put("message", message));
		ws.writeTextMessage(Json.encode(error));
	}
}
