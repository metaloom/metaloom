package io.metaloom.loom.rest.service.impl;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.auth.AuthLoginRequest;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.json.JsonObject;

@Singleton
public class AuthenticationEndpointService extends AbstractEndpointService {

	private final AuthenticationService authService;
	private final LoomOptions options;

	@Inject
	public AuthenticationEndpointService(AuthenticationService authService, LoomOptions options, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.authService = authService;
		this.options = options;
	}

	public void login(LoomRoutingContext lrc) {
		AuthLoginRequest request = lrc.requestBody(AuthLoginRequest.class);
		validator.validate(request);

		User user = authService.login(request.getUsername(), request.getPassword());
		if (user == null) {
			lrc.send(new GenericMessageResponse().setMessage("Login failed"), 401);
		} else {
			String token = authService.generate(new JsonObject().put("uuid", user.getUuid().toString()));
			setTokenCookie(lrc, token);
			AuthLoginResponse response = new AuthLoginResponse();
			response.setToken(token);
			lrc.send(response);
		}
	}

	/**
	 * Set the Loom JWT as an HttpOnly secure cookie following draft-ietf-oauth-browser-based-apps-21 §6.1.3.2.
	 */
	private void setTokenCookie(LoomRoutingContext lrc, String token) {
		AuthenticationOptions authOptions = options.getAuth();
		lrc.routingContext().response().addCookie(
			Cookie.cookie(AuthenticationOptions.TOKEN_COOKIE_KEY, token)
				.setHttpOnly(true)
				.setSecure(true)
				.setSameSite(CookieSameSite.STRICT)
				.setMaxAge(authOptions.getTokenExpirationTime())
				.setPath("/"));
	}

}
