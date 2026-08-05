package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;

import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.auth.LoomUser;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.WebSocketAuthenticator;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.auth.User;

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
 *
 * <h2>Authentication</h2>
 * <p>A bearer token can be passed as the {@code ?token=&lt;jwt&gt;} query parameter on
 * the WebSocket handshake URL. When present the token is validated via
 * {@link WebSocketAuthenticator}; invalid tokens result in a
 * {@code 4401} close code.</p>
 *
 * <h2>Per-pipeline filtering</h2>
 * <p>Clients may pass {@code ?pipeline=&lt;name&gt;} to receive only events for the
 * specified pipeline. When omitted, all pipeline events are delivered.</p>
 */
public class PipelineEventEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(PipelineEventEndpoint.class);

	private final PipelineEventBroadcaster broadcaster;
	private final WebSocketAuthenticator authenticator;

	@Inject
	public PipelineEventEndpoint(PipelineEventBroadcaster broadcaster, WebSocketAuthenticator authenticator,
		EndpointDependencies deps) {
		super(deps);
		this.broadcaster = broadcaster;
		this.authenticator = authenticator;
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

		// WebSocket upgrade route. Authentication is done post-upgrade because
		// the WS handshake cannot carry a full auth header from browsers — the
		// token is passed as a `?token=` query parameter.
		// Use a high-priority route order so the WS upgrade path is matched before
		// wildcard auth routes such as /api/v1/pipelines*.
		apiRouter().getDelegate().get(basePath() + "/ws").order(-1000).handler(rc -> {
			rc.request().toWebSocket()
				.onSuccess(this::handleWebSocket)
				.onFailure(err -> {
					log.warn("Pipeline events WebSocket upgrade failed", err);
					rc.response().setStatusCode(400).end("WebSocket upgrade failed");
				});
		});
	}

	private void handleWebSocket(ServerWebSocket ws) {
		authenticator.authenticate(ws, "pipeline-events")
			.onSuccess(user -> {
				broadcaster.addSubscriber(ws, extractQueryParam(ws, "pipeline"), extractQueryParam(ws, "run"),
					resolveUserUuid(ws, user));

				ws.closeHandler(v2 -> broadcaster.removeSubscriber(ws));

				ws.exceptionHandler(err -> {
					log.error("Pipeline events WebSocket error", err);
					broadcaster.removeSubscriber(ws);
				});
			})
			.onFailure(err -> {
				// authenticator already logged + closed the socket
				log.debug("Pipeline events WebSocket rejected: {}", err.getMessage());
			});
	}

	/**
	 * Extract an optional query parameter from the WebSocket handshake URL.
	 *
	 * <p>Two are understood: {@code ?pipeline=<name>} narrows the stream to one pipeline, and
	 * {@code ?run=<uuid>} narrows it to a single run. The second matters for a CLI following
	 * one run - a pipeline-name filter still delivers every concurrent run of that pipeline,
	 * which on a busy pipeline is most of the traffic.</p>
	 *
	 * @param ws  the socket
	 * @param key the parameter name
	 * @return the decoded value, or null when absent
	 */
	private static String extractQueryParam(ServerWebSocket ws, String key) {
		String query = ws.query();
		if (query == null || query.isEmpty()) {
			return null;
		}
		for (String part : query.split("&")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			if (key.equals(part.substring(0, eq))) {
				return java.net.URLDecoder.decode(part.substring(eq + 1),
					java.nio.charset.StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	/**
	 * Resolve the uuid of the authenticated user, or null when the socket carries no usable identity.
	 *
	 * <p>
	 * Two ways that happens. A tokenless socket is accepted outright in lenient mode (the default), and arrives here with a null user. And
	 * {@link LoomUser#getUuid()} is {@code UUID.fromString(get("uuid"))}, which throws on a token that has no {@code uuid} claim or a malformed one.
	 * That throw <b>must</b> be caught: it would otherwise escape the {@code onSuccess} handler, leaving a live socket with no {@code closeHandler}
	 * registered and therefore a subscriber that is never removed.
	 * </p>
	 *
	 * <p>
	 * A null result is safe by construction — {@code broadcastNotification} never matches a null-user subscriber.
	 * </p>
	 */
	private static UUID resolveUserUuid(ServerWebSocket ws, User user) {
		if (user == null) {
			return null;
		}
		try {
			return new LoomUser(user).getUuid();
		} catch (RuntimeException e) {
			log.warn("Pipeline events WebSocket from {} authenticated but carries no usable uuid claim; "
				+ "it will receive no notifications", ws.remoteAddress());
			return null;
		}
	}
}
