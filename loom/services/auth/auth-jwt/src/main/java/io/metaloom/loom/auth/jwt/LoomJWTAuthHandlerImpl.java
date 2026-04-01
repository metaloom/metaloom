package io.metaloom.loom.auth.jwt;

import javax.inject.Inject;

import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.JWTAuthHandler;

public class LoomJWTAuthHandlerImpl implements LoomAuthenticationHandler {

	private JWTAuthHandler jwtHandler;

	@Inject
	public LoomJWTAuthHandlerImpl(JWTAuth authProvider) {
		this.jwtHandler = JWTAuthHandler.create(authProvider);
	}

	@Override
	public void handle(RoutingContext rc) {
		jwtHandler.handle(rc);
	}

}
