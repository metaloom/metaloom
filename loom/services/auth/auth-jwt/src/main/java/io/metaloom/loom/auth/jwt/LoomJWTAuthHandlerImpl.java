package io.metaloom.loom.auth.jwt;

import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;

import java.util.regex.Pattern;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
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
	private final LoomOptions options;

	@Inject
	public LoomJWTAuthHandlerImpl(JWTAuth authProvider, LoomOptions options) {
		this.authProvider = authProvider;
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
				((UserContextInternal) context.userContext()).setUser(authenticatedUser);
				refreshTokenCookie(context, authenticatedUser.principal());
				context.next();
			})
			.onFailure(err -> {
				if (log.isDebugEnabled()) {
					log.debug("JWT authentication failed", err);
				}
				handle401(context);
			});
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
}

