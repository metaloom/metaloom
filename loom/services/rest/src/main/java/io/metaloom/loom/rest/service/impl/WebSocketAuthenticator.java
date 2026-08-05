package io.metaloom.loom.rest.service.impl;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.auth.User;

/**
 * Validates a JWT bearer token supplied on a WebSocket handshake as the
 * {@code ?token=} query parameter.
 *
 * <p>When a token is present, it is validated via
 * {@link LoomAuthenticationHandler#authenticateToken(String)} which uses the
 * same underlying JWT provider as the REST auth handler. When absent, the
 * connection is accepted with a warning so pre-token clients keep working
 * during the migration; set {@code LOOM_WS_STRICT_AUTH=true} (or JVM property
 * {@code loom.ws.strictAuth}) to require a token on every connection.</p>
 *
 * <p>Close codes:</p>
 * <ul>
 *   <li>{@code 4401} — unauthorized (missing token in strict mode, or invalid token)</li>
 * </ul>
 */
@Singleton
public class WebSocketAuthenticator {

	/** Close code used when the WebSocket is rejected due to auth failure. */
	public static final int UNAUTHORIZED_CLOSE_CODE = 4401;

	private static final Logger log = LoggerFactory.getLogger(WebSocketAuthenticator.class);

	private final LoomAuthenticationHandler authHandler;
	private final boolean strict;
	private final io.metaloom.loom.common.metrics.LoomMetrics metrics;

	@Inject
	public WebSocketAuthenticator(LoomAuthenticationHandler authHandler, LoomOptions options,
		io.metaloom.loom.common.metrics.LoomMetrics metrics) {
		this.authHandler = authHandler;
		this.strict = resolveStrict(options);
		this.metrics = metrics;
	}

	private static boolean resolveStrict(LoomOptions options) {
		String prop = System.getProperty("loom.ws.strictAuth");
		if (prop == null || prop.isBlank()) {
			prop = System.getenv("LOOM_WS_STRICT_AUTH");
		}
		if (prop != null && !prop.isBlank()) {
			return Boolean.parseBoolean(prop.trim());
		}
		return false;
	}

	/**
	 * Authenticate a freshly upgraded WebSocket.
	 *
	 * <p>Returns a succeeded future when the socket is authorised. When
	 * authentication fails the socket is closed with
	 * {@link #UNAUTHORIZED_CLOSE_CODE} and the returned future is failed.</p>
	 *
	 * @param ws       the freshly upgraded socket
	 * @param endpoint short label used in log messages (e.g. "pipeline-events")
	 * @return a future that resolves when the socket is deemed authorised
	 */
	public Future<User> authenticate(ServerWebSocket ws, String endpoint) {
		String token = extractToken(ws);
		if (token == null || token.isBlank()) {
			if (strict) {
				metrics.recordAuthFailure("ws");
				close(ws, "missing token");
				log.warn("Rejecting {} WebSocket from {}: no token supplied", endpoint, ws.remoteAddress());
				return Future.failedFuture("Missing token");
			}
			log.warn("Accepting {} WebSocket from {} without token (strict auth disabled)", endpoint, ws.remoteAddress());
			return Future.succeededFuture(null);
		}
		Promise<User> promise = Promise.promise();
		authHandler.authenticateToken(token)
			.onSuccess(user -> {
				log.debug("Authenticated {} WebSocket from {}", endpoint, ws.remoteAddress());
				promise.complete(user);
			})
			.onFailure(err -> {
				metrics.recordAuthFailure("ws");
				close(ws, "invalid token");
				log.warn("Rejecting {} WebSocket from {}: invalid token ({})", endpoint, ws.remoteAddress(), err.getMessage());
				promise.fail(err);
			});
		return promise.future();
	}

	private static String extractToken(ServerWebSocket ws) {
		String q = ws.query();
		if (q == null || q.isEmpty()) {
			return null;
		}
		for (String part : q.split("&")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = part.substring(0, eq);
			if ("token".equals(key)) {
				return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static void close(ServerWebSocket ws, String reason) {
		try {
			ws.close((short) UNAUTHORIZED_CLOSE_CODE, reason);
		} catch (Exception e) {
			// Ignore — connection may already be closing.
		}
	}
}
