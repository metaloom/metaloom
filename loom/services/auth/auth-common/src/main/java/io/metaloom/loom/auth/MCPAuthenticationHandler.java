package io.metaloom.loom.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.token.TokenDao;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;

/**
 * Authentication handler for MCP (Model Context Protocol) endpoints.
 *
 * <p>Supports multiple authentication mechanisms across different transports:</p>
 * <ul>
 *   <li><strong>SSE (/mcp/sse)</strong>: Token via {@code ?token=} query parameter OR {@code Authorization: Bearer} header</li>
 *   <li><strong>Message (/mcp/message)</strong>: Token via {@code Authorization: Bearer} header OR {@code X-API-Key} header</li>
 *   <li><strong>WebSocket (/mcp/ws)</strong>: Token via {@code ?token=} query parameter (handled by {@link WebSocketAuthenticator})</li>
 * </ul>
 *
 * <p>Authentication can be enabled/disabled via {@code LOOM_MCP_AUTH_ENABLED} and
 * strict/lenient mode via {@code LOOM_MCP_AUTH_STRICT_MODE}. In lenient mode,
 * requests without valid credentials are accepted but logged with a warning.</p>
 */
@Singleton
public class MCPAuthenticationHandler {

	private static final Logger log = LoggerFactory.getLogger(MCPAuthenticationHandler.class);

	private static final String X_API_KEY_HEADER = "X-API-Key";
	private static final String TOKEN_QUERY_PARAM = "token";

	private final LoomAuthenticationHandler authHandler;
	private final TokenDao tokenDao;
	private final AuthenticationOptions authOptions;
	private final boolean enabled;
	private final boolean strictMode;
	private final List<String> allowedOrigins;

	@Inject
	public MCPAuthenticationHandler(LoomAuthenticationHandler authHandler, TokenDao tokenDao, LoomOptions options) {
		this.authHandler = authHandler;
		this.tokenDao = tokenDao;
		this.authOptions = options.getAuth();
		this.enabled = authOptions.isMcpAuthEnabled();
		this.strictMode = authOptions.isMcpAuthStrictMode();
		this.allowedOrigins = parseAllowedOrigins(authOptions.getMcpAuthAllowedOrigins());
	}

	private static List<String> parseAllowedOrigins(String origins) {
		if (origins == null || origins.isBlank()) {
			return List.of("*");
		}
		return Arrays.stream(origins.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.toList();
	}

	/**
	 * Check if authentication is enabled for MCP endpoints.
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Check if strict mode is enabled (reject requests without valid auth).
	 */
	public boolean isStrictMode() {
		return strictMode;
	}

	/**
	 * Get the list of allowed origins for CORS on SSE endpoint.
	 */
	public List<String> getAllowedOrigins() {
		return allowedOrigins;
	}

	/**
	 * Authenticate an HTTP request for MCP SSE or message endpoint.
	 *
	 * <p>For SSE: accepts token via {@code ?token=} query param OR {@code Authorization: Bearer} header</p>
 * <p>For message: requires {@code Authorization: Bearer} header OR {@code X-API-Key} header</p>
	 *
	 * @param context the routing context
	 * @param endpointType the endpoint type ("sse" or "message") for logging
	 * @return future that succeeds with the authenticated User or fails with auth error
	 */
	public Future<User> authenticateHttp(RoutingContext context, String endpointType) {
		if (!enabled) {
			return Future.succeededFuture(null);
		}

		String token = extractToken(context, endpointType);
		if (token == null || token.isBlank()) {
			if (strictMode) {
				log.warn("Rejecting MCP {} request from {}: no credentials supplied", endpointType, context.request().remoteAddress());
				return Future.failedFuture("Missing authentication credentials");
			}
			log.warn("Accepting MCP {} request from {} without credentials (strict auth disabled)", endpointType, context.request().remoteAddress());
			return Future.succeededFuture(null);
		}

		// Try JWT token first
		return authHandler.authenticateToken(token)
			.recover(err -> {
				// If JWT validation fails, try API key
				log.debug("JWT validation failed, trying API key: {}", err.getMessage());
				return validateApiKey(token);
			})
			.onSuccess(user -> {
				if (user != null) {
					log.debug("Authenticated MCP {} request from {}", endpointType, context.request().remoteAddress());
				}
			})
			.onFailure(err -> {
				log.warn("Rejecting MCP {} request from {}: {}", endpointType, context.request().remoteAddress(), err.getMessage());
			});
	}

	/**
	 * Extract token from request based on endpoint type.
	 */
	private String extractToken(RoutingContext context, String endpointType) {
		// For SSE: check query param first, then Authorization header
		if ("sse".equals(endpointType)) {
			String queryToken = context.request().getParam(TOKEN_QUERY_PARAM);
			if (queryToken != null && !queryToken.isBlank()) {
				return queryToken;
			}
		}

		// Check Authorization: Bearer header
		String authHeader = context.request().headers().get(HttpHeaders.AUTHORIZATION);
		if (authHeader != null) {
			String[] parts = authHeader.split(" ");
			if (parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0])) {
				return parts[1];
			}
		}

		// For message endpoint: check X-API-Key header
		if ("message".equals(endpointType)) {
			String apiKey = context.request().headers().get(X_API_KEY_HEADER);
			if (apiKey != null && !apiKey.isBlank()) {
				return apiKey;
			}
		}

		return null;
	}

	/**
	 * Validate an API key against the TokenDao.
	 */
	private Future<User> validateApiKey(String apiKey) {
		return tokenDao.findByToken(apiKey)
			.map(optionalToken -> optionalToken
				.map(token -> User.create(new io.vertx.core.json.JsonObject().put("uuid", token.getUserUuid())))
				.orElse(null));
	}

	/**
	 * Apply CORS headers for SSE endpoint based on allowed origins.
	 */
	public void applyCorsHeaders(RoutingContext context) {
		if (!enabled) {
			// When auth is disabled, allow all origins (original behavior)
			context.response().putHeader("Access-Control-Allow-Origin", "*");
			return;
		}

		String origin = context.request().headers().get("Origin");
		if (origin != null && isOriginAllowed(origin)) {
			context.response().putHeader("Access-Control-Allow-Origin", origin);
			context.response().putHeader("Access-Control-Allow-Credentials", "true");
		} else if (allowedOrigins.contains("*")) {
			context.response().putHeader("Access-Control-Allow-Origin", "*");
		} else {
			// Origin not allowed - don't set CORS headers (will cause browser to block)
			log.debug("CORS: Origin '{}' not in allowed list for MCP SSE", origin);
		}
	}

	private boolean isOriginAllowed(String origin) {
		return allowedOrigins.stream().anyMatch(allowed -> {
			if ("*".equals(allowed)) {
				return true;
			}
			// Support wildcard subdomain matching (e.g., "https://*.example.com")
			if (allowed.contains("*")) {
				String regex = allowed.replace(".", "\\.").replace("*", ".*");
				return origin.matches(regex);
			}
			return allowed.equals(origin);
		});
	}
}