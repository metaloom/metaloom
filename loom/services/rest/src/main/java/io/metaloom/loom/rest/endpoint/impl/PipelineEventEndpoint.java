package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.vertx.core.http.ServerWebSocket;

/**
 * WebSocket endpoint that streams live pipeline tracking events to UI clients.
 *
 * <p>UI clients connect to {@code /api/v1/pipelines/events/ws} and immediately
 * start receiving JSON-encoded {@link io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage}
 * objects as processor nodes report pipeline activity.</p>
 *
 * <p>The endpoint is read-only from the client side — clients do not send messages.
 * Events flow from processor nodes via the {@link io.metaloom.loom.rest.endpoint.impl.ProcessorEndpoint}
 * into the {@link PipelineEventBroadcaster} and then fan out to all connected subscribers.</p>
 */
public class PipelineEventEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(PipelineEventEndpoint.class);

	private final PipelineEventBroadcaster broadcaster;

	@Inject
	public PipelineEventEndpoint(PipelineEventBroadcaster broadcaster, EndpointDependencies deps) {
		super(deps);
		this.broadcaster = broadcaster;
	}

	@Override
	public String name() {
		return "pipeline-events";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/pipelines/events";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// WebSocket upgrade route — not secured via auth handler since WS upgrade
		// happens before the handler chain. Authentication can be enforced by requiring
		// a token query parameter in the future.
		apiRouter().getDelegate().get(basePath() + "/ws").handler(rc -> {
			rc.request().toWebSocket()
				.onSuccess(this::handleWebSocket)
				.onFailure(err -> {
					log.warn("Pipeline events WebSocket upgrade failed", err);
					rc.response().setStatusCode(400).end("WebSocket upgrade failed");
				});
		});
	}

	private void handleWebSocket(ServerWebSocket ws) {
		broadcaster.addSubscriber(ws);

		ws.closeHandler(v -> broadcaster.removeSubscriber(ws));

		ws.exceptionHandler(err -> {
			log.error("Pipeline events WebSocket error", err);
			broadcaster.removeSubscriber(ws);
		});
	}
}
