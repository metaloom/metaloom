package io.metaloom.loom.rest.service.impl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.OAuth2Options;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2AuthorizationURL;
import io.vertx.ext.auth.oauth2.Oauth2Credentials;
import io.vertx.ext.web.RoutingContext;

/**
 * OAuth2 endpoint service implementing the BFF pattern from draft-ietf-oauth-browser-based-apps-21.
 *
 * Flow:
 * 1. Browser navigates to /api/v1/auth/oauth2/login
 * 2. BFF redirects to IdP authorization endpoint (with PKCE)
 * 3. IdP redirects back to /api/v1/auth/oauth2/callback with authorization code
 * 4. BFF exchanges code for tokens, resolves user identity, issues a Loom JWT in HttpOnly cookie
 * 5. Browser is redirected to the frontend application
 */
@Singleton
public class OAuth2EndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(OAuth2EndpointService.class);

	private static final String PKCE_VERIFIER_COOKIE = "__Host-loom_pkce_verifier";
	private static final String OAUTH2_STATE_COOKIE = "__Host-loom_oauth2_state";

	private final OAuth2Auth oauth2Provider;
	private final AuthenticationService authService;
	private final UserDao userDao;
	private final LoomOptions options;
	private final SecureRandom secureRandom = new SecureRandom();

	@Inject
	public OAuth2EndpointService(OAuth2Auth oauth2Provider, AuthenticationService authService, UserDao userDao,
		LoomOptions options, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.oauth2Provider = oauth2Provider;
		this.authService = authService;
		this.userDao = userDao;
		this.options = options;
	}

	/**
	 * Initiate the OAuth2 Authorization Code flow with PKCE.
	 * Redirects the browser to the IdP authorization endpoint.
	 */
	public void login(LoomRoutingContext lrc) {
		RoutingContext rc = lrc.routingContext();
		OAuth2Options oauth2Options = options.getAuth().getOauth2();

		// Generate PKCE code verifier and challenge
		String codeVerifier = generateCodeVerifier();
		String codeChallenge = generateCodeChallenge(codeVerifier);
		String state = generateState();

		// Store the PKCE verifier and state in HttpOnly cookies (not accessible to JS)
		rc.response().addCookie(
			Cookie.cookie(PKCE_VERIFIER_COOKIE, codeVerifier)
				.setHttpOnly(true)
				.setSecure(true)
				.setSameSite(CookieSameSite.LAX) // LAX needed for redirect back from IdP
				.setMaxAge(600) // 10 minutes for the OAuth flow
				.setPath("/"));

		rc.response().addCookie(
			Cookie.cookie(OAUTH2_STATE_COOKIE, state)
				.setHttpOnly(true)
				.setSecure(true)
				.setSameSite(CookieSameSite.LAX)
				.setMaxAge(600)
				.setPath("/"));

		String authorizationUrl = oauth2Provider.authorizeURL(new OAuth2AuthorizationURL()
			.setRedirectUri(oauth2Options.getCallbackUrl())
			.setScopes(Arrays.asList(oauth2Options.getScope().split(" ")))
			.setState(state)
			.setCodeChallenge(codeChallenge)
			.setCodeChallengeMethod("S256"));

		rc.response()
			.setStatusCode(302)
			.putHeader("Location", authorizationUrl)
			.putHeader("Cache-Control", "no-store")
			.end();
	}

	/**
	 * Handle the OAuth2 callback from the IdP.
	 * Exchanges the authorization code for tokens, resolves the user, and sets the Loom JWT cookie.
	 */
	public void callback(LoomRoutingContext lrc) {
		RoutingContext rc = lrc.routingContext();
		OAuth2Options oauth2Options = options.getAuth().getOauth2();

		// Validate state parameter (CSRF protection)
		String state = rc.request().getParam("state");
		Cookie stateCookie = rc.request().getCookie(OAUTH2_STATE_COOKIE);
		if (stateCookie == null || state == null || !state.equals(stateCookie.getValue())) {
			log.warn("OAuth2 callback: state mismatch");
			rc.response().setStatusCode(403).end("Invalid state parameter");
			return;
		}

		// Check for error from IdP
		String error = rc.request().getParam("error");
		if (error != null) {
			String errorDescription = rc.request().getParam("error_description");
			log.warn("OAuth2 callback error: {} - {}", error, errorDescription);
			rc.response().setStatusCode(400).end("OAuth2 error: " + error);
			return;
		}

		String code = rc.request().getParam("code");
		if (code == null) {
			rc.response().setStatusCode(400).end("Missing authorization code");
			return;
		}

		// Retrieve PKCE code verifier from cookie
		Cookie verifierCookie = rc.request().getCookie(PKCE_VERIFIER_COOKIE);
		if (verifierCookie == null) {
			log.warn("OAuth2 callback: missing PKCE verifier cookie");
			rc.response().setStatusCode(400).end("Missing PKCE verifier");
			return;
		}
		String codeVerifier = verifierCookie.getValue();

		// Clean up PKCE and state cookies
		rc.response().removeCookie(PKCE_VERIFIER_COOKIE);
		rc.response().removeCookie(OAUTH2_STATE_COOKIE);

		// Exchange authorization code for tokens
		Oauth2Credentials credentials = new Oauth2Credentials()
			.setCode(code)
			.setRedirectUri(oauth2Options.getCallbackUrl())
			.setCodeVerifier(codeVerifier);

		oauth2Provider.authenticate(credentials)
			.onSuccess(oauth2User -> {
				// Extract user information from the OAuth2 token / userinfo
				JsonObject idTokenClaims = oauth2User.principal();

				// Try to find or create the user based on OAuth2 claims
				String email = idTokenClaims.getString("email");
				String preferredUsername = idTokenClaims.getString("preferred_username", email);
				String sub = idTokenClaims.getString("sub");

				if (preferredUsername == null && email == null) {
					log.error("OAuth2: No username or email found in token claims");
					rc.response().setStatusCode(500).end("Could not determine user identity");
					return;
				}

				// First try by username, then by email, then create
				String lookupName = preferredUsername != null ? preferredUsername : email;
				User user = userDao.loadByUsername(lookupName);
				if (user == null && email != null) {
					// Try to find by email in case username differs
					user = userDao.loadByUsername(email);
				}

				if (user == null) {
					// Auto-create SSO user
					log.info("Creating new SSO user: {}", lookupName);
					user = userDao.createUser(lookupName);
					user.setSSO(true);
					user.setEnabled(true);
					if (email != null) {
						user.setEmail(email);
					}
					String firstname = idTokenClaims.getString("given_name");
					String lastname = idTokenClaims.getString("family_name");
					if (firstname != null) {
						user.setFirstname(firstname);
					}
					if (lastname != null) {
						user.setLastname(lastname);
					}
				}

				if (!user.isEnabled()) {
					log.warn("OAuth2 login attempted for disabled user: {}", lookupName);
					rc.response().setStatusCode(403).end("User account is disabled");
					return;
				}

				// Generate a Loom-specific JWT and set it as HttpOnly cookie
				String loomToken = authService.generate(new JsonObject().put("uuid", user.getUuid().toString()));
				AuthenticationOptions authOptions = options.getAuth();
				rc.response().addCookie(
					Cookie.cookie(AuthenticationOptions.TOKEN_COOKIE_KEY, loomToken)
						.setHttpOnly(true)
						.setSecure(true)
						.setSameSite(CookieSameSite.STRICT)
						.setMaxAge(authOptions.getTokenExpirationTime())
						.setPath("/"));

				// Redirect to frontend application
				rc.response()
					.setStatusCode(302)
					.putHeader("Location", "/")
					.putHeader("Cache-Control", "no-store")
					.end();
			})
			.onFailure(err -> {
				log.error("OAuth2 token exchange failed", err);
				rc.response().setStatusCode(500).end("Token exchange failed");
			});
	}

	/**
	 * Logout endpoint: clears the Loom JWT cookie.
	 */
	public void logout(LoomRoutingContext lrc) {
		RoutingContext rc = lrc.routingContext();
		rc.response().removeCookie(AuthenticationOptions.TOKEN_COOKIE_KEY);
		rc.response()
			.setStatusCode(200)
			.putHeader("Cache-Control", "no-store")
			.end(new JsonObject().put("message", "Logged out").encode());
	}

	/**
	 * Generate a cryptographically random PKCE code verifier (RFC 7636).
	 */
	private String generateCodeVerifier() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * Generate a PKCE code challenge from a code verifier using S256.
	 */
	private String generateCodeChallenge(String codeVerifier) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	/**
	 * Generate a cryptographically random state parameter for CSRF protection.
	 */
	private String generateState() {
		byte[] bytes = new byte[24];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
