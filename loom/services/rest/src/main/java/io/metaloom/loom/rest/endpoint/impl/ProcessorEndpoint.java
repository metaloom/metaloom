package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.processor.ProcessorListResponse;
import io.metaloom.loom.rest.model.processor.ProcessorResponse;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.SystemStatusInfo;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;
import io.metaloom.loom.rest.service.impl.WebSocketAuthenticator;
import io.metaloom.loom.rest.service.impl.WorkOrderResultRegistry;
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
 *   <li>Loom sends {@code WORK_ORDER} messages to dispatch work</li>
 *   <li>Processor sends {@code WORK_ORDER_RESULT} when work completes</li>
 * </ol>
 */
public class ProcessorEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ProcessorEndpoint.class);

	private final ProcessorRegistry registry;
	private final PipelineEventBroadcaster pipelineEventBroadcaster;
	private final WebSocketAuthenticator authenticator;
	private final WorkOrderResultRegistry workOrderResultRegistry;
	private final ModelExamples examples;

	@Inject
	public ProcessorEndpoint(ProcessorRegistry registry, PipelineEventBroadcaster pipelineEventBroadcaster,
			WebSocketAuthenticator authenticator, WorkOrderResultRegistry workOrderResultRegistry,
			EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.registry = registry;
		this.pipelineEventBroadcaster = pipelineEventBroadcaster;
		this.authenticator = authenticator;
		this.workOrderResultRegistry = workOrderResultRegistry;
		this.examples = examples;
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

		// List all registered processors
		addListRoute(basePath(), GET,
			"Load a list of registered processor nodes",
			examples.processorListResponseExample(),
			lrc -> {
				ProcessorListResponse list = new ProcessorListResponse();
				for (ConnectedProcessor p : registry.getAll()) {
					list.add(registry.toResponse(p));
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
					// Try to find by UUID
					for (ConnectedProcessor p : registry.getAll()) {
						ProcessorResponse resp = registry.toResponse(p);
						if (resp.getUuid().toString().equals(uuid)) {
							lrc.send(registry.toResponse(p), 200);
							return;
						}
					}
					lrc.sendText("{\"message\":\"Processor not found\"}", "application/json", 404);
					return;
				}
				lrc.send(registry.toResponse(processor), 200);
			});
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
				case WORK_ORDER_RESULT:
					handleWorkOrderResult(ws, msg, nodeIdHolder[0]);
					break;
				case PIPELINE_EVENT:
					handlePipelineEvent(ws, msg, nodeIdHolder[0]);
					break;
				default:
					sendError(ws, "Unexpected message type: " + msg.getType());
					break;
			}
		});

		ws.closeHandler(v -> {
			if (nodeIdHolder[0] != null) {
				log.info("Processor disconnected: {}", nodeIdHolder[0]);
				registry.updateState(nodeIdHolder[0], ProcessorState.OFFLINE);
				registry.unregister(nodeIdHolder[0]);
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

	private void handleWorkOrderResult(ServerWebSocket ws, ProcessorMessage msg, String nodeId) {
		if (nodeId == null) {
			sendError(ws, "Not registered. Send REGISTER first.");
			return;
		}
		if (msg.getBody() == null) {
			sendError(ws, "WORK_ORDER_RESULT message must include a body");
			return;
		}
		WorkOrderResult result = msg.getBody().mapTo(WorkOrderResult.class);
		log.info("Work order result received from {}: workOrderId={}, status={}",
			nodeId, result.getWorkOrderId(), result.getStatus());
		boolean routed = workOrderResultRegistry.complete(result);
		if (!routed) {
			log.debug("No registered callback for work order {} (result logged only)",
				result.getWorkOrderId());
		}
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

	private void sendError(ServerWebSocket ws, String message) {
		ProcessorMessage error = new ProcessorMessage(ProcessorMessageType.ERROR,
			new JsonObject().put("message", message));
		ws.writeTextMessage(Json.encode(error));
	}
}
