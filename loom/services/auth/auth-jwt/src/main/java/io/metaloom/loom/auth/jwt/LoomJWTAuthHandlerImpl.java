package io.metaloom.loom.auth.jwt;

import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;

import java.util.regex.Pattern;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.metaloom.loom.db.model.token.TokenDao;
import io.vertx.core.Future;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;

/**
 * Stateless JWT authentication handler that reads tokens from HttpOnly cookies (preferred) or the Authorization header.
 * On each successful authentication the JWT is refreshed and a new HttpOnly cookie is set.
 * 
 * Cookie security follows draft-ietf-oauth-browser-based-apps-21 §6.1.3.2:
 * <ul>
 *   <li>HttpOnly – prevents JavaScript access</li>
 *   <li>Secure – cookie only sent over HTTPS</li>
 *   <li>SameSite=Strict – mitigates CSRF</li>
 *   <li>Path=/</li>
 *   <li>__Host- prefix</li>
 * </ul>
 */
public class LoomJWTAuthHandlerImpl implements LoomAuthenticationHandler {

	private static final Logger log = LoggerFactory.getLogger(LoomJWTAuthHandlerImpl.class);

	private static final Pattern BEARER = Pattern.compile("^Bearer$", Pattern.CASE_INSENSITIVE);

	private final JWTAuth authProvider;
	private final TokenDao tokenDao;
	private final LoomOptions options;

	@Inject
	public LoomJWTAuthHandlerImpl(JWTAuth authProvider, TokenDao tokenDao, LoomOptions options) {
		this.authProvider = authProvider;
		this.tokenDao = tokenDao;
		this.options = options;
	}

	@Override
	public void handle(RoutingContext context) {
		// Skip if user is already authenticated by a previous handler
		if (context.user() != null) {
			context.next();
			return;
		}

		String token = extractToken(context);
		if (token == null) {
			handle401(context);
			return;
		}

		// Validate the JWT
		authProvider.authenticate(new TokenCredentials(token))
			.onSuccess(authenticatedUser -> {
				// A media token is signed by the same key, so it would otherwise authenticate every
				// route in the API. It must not: it travels in a query string, where a proxy log, a
				// browser history or a pasted URL will carry it further than a session ever goes.
				// MediaTokenAuthHandler is the only place it is accepted, and only for the asset it
				// names.
				if (MediaTokenAuthHandler.SCOPE_MEDIA.equals(authenticatedUser.principal().getString(MediaTokenAuthHandler.CLAIM_SCOPE))) {
					log.warn("Rejected a media-scoped token presented as a session credential.");
					handle401(context);
					return;
				}
				((UserContextInternal) context.userContext()).setUser(authenticatedUser);
				refreshTokenCookie(context, authenticatedUser.principal());
				context.next();
			})
			.onFailure(jwtErr -> {
				// Not a valid JWT - fall back to a long-lived API key (POST /api/v1/tokens). No
				// cookie is issued for an API key: it is a bearer credential for non-browser
				// clients (e.g. a Cortex worker), not a browser session.
				validateApiKey(token)
					.onSuccess(apiKeyUser -> {
						if (apiKeyUser == null) {
							if (log.isDebugEnabled()) {
								log.debug("JWT authentication failed", jwtErr);
							}
							handle401(context);
							return;
						}
						((UserContextInternal) context.userContext()).setUser(apiKeyUser);
						context.next();
					})
					.onFailure(apiKeyErr -> {
						if (log.isDebugEnabled()) {
							log.debug("JWT authentication failed", jwtErr);
						}
						handle401(context);
					});
			});
	}

	/**
	 * Validate an API key against the TokenDao. Resolves to the owning user (the token's
	 * {@code creator_uuid}); the token record has no separate user column, so the creator is
	 * authoritative for permission resolution - mirrors {@code MCPAuthenticationHandler#validateApiKey}.
	 */
	private Future<User> validateApiKey(String apiKey) {
		return tokenDao.findByToken(apiKey)
			.map(optionalToken -> optionalToken
				.map(t -> {
					var userUuid = t.getCreatorUuid();
					if (userUuid == null) {
						return (User) null;
					}
					return User.create(new JsonObject().put("uuid", userUuid.toString()));
				})
				.orElse(null));
	}

	/**
	 * Extract JWT token from HttpOnly cookie first, then fall back to Authorization header.
	 */
	private String extractToken(RoutingContext context) {
		// 1. Try cookie (preferred for browser-based apps – HttpOnly, not accessible to JS)
		Cookie tokenCookie = context.request().getCookie(AuthenticationOptions.TOKEN_COOKIE_KEY);
		if (tokenCookie != null) {
			return tokenCookie.getValue();
		}

		// 2. Fall back to Authorization: Bearer <token> header (for API/non-browser clients)
		final HttpServerRequest request = context.request();
		final String authorization = request.headers().get(AUTHORIZATION);
		if (authorization != null) {
			String[] parts = authorization.split(" ");
			if (parts.length == 2 && BEARER.matcher(parts[0]).matches()) {
				return parts[1];
			}
			log.warn("Malformed Authorization header. Expected format: Bearer [token]");
		}
		return null;
	}

	/**
	 * Re-generate the JWT with a fresh expiry and set it as an HttpOnly cookie.
	 */
	private void refreshTokenCookie(RoutingContext context, JsonObject principal) {
		AuthenticationOptions authOptions = options.getAuth();
		int expirationTime = authOptions.getTokenExpirationTime();

		// Carry over existing claims (e.g. uuid) into fresh token
		String freshToken = authProvider.generateToken(principal, new JWTOptions().setExpiresInSeconds(expirationTime));

		// Set a fresh cookie (addCookie replaces any existing cookie with the same name)
		context.response().addCookie(
			Cookie.cookie(AuthenticationOptions.TOKEN_COOKIE_KEY, freshToken)
				.setHttpOnly(true)
				.setSecure(true)
				.setSameSite(CookieSameSite.STRICT)
				.setMaxAge(expirationTime)
				.setPath("/"));
	}

	private void handle401(RoutingContext context) {
		context.response()
			.setStatusCode(401)
			.putHeader("WWW-Authenticate", "Bearer")
			.end("Unauthorized");
	}

	@Override
	public Future<User> authenticateToken(String token) {
		return authProvider.authenticate(new TokenCredentials(token));
	}
}

