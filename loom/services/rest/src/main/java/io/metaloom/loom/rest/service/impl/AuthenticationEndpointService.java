package io.metaloom.loom.rest.service.impl;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.auth.AuthLoginRequest;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.json.JsonObject;

@Singleton
public class AuthenticationEndpointService extends AbstractEndpointService {

	private final AuthenticationService authService;

	@Inject
	public AuthenticationEndpointService(AuthenticationService authService, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.authService = authService;
	}

	public void login(LoomRoutingContext lrc) {
		AuthLoginRequest request = lrc.requestBody(AuthLoginRequest.class);
		validator.validate(request);;

		User user = authService.login(request.getUsername(), request.getPassword());
		if (user == null) {
			lrc.send(new GenericMessageResponse().setMessage("Login failed"), 401);
		} else {
			String token = authService.generate(new JsonObject().put("uuid", user.getUuid().toString()));
			AuthLoginResponse response = new AuthLoginResponse();
			response.setToken(token);
			lrc.send(response);
		}
	}

}
