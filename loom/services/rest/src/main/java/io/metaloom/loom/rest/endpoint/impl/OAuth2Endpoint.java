package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.service.impl.OAuth2EndpointService;

public class OAuth2Endpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(OAuth2Endpoint.class);

	private final OAuth2EndpointService service;

	@Inject
	public OAuth2Endpoint(EndpointDependencies deps, OAuth2EndpointService oauth2Service) {
		super(deps);
		this.service = oauth2Service;
	}

	@Override
	public String name() {
		return "oauth2";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/auth/oauth2";
	}

	@Override
	public void register() {
		log.info("Registering OAuth2 endpoints");

		// Initiate OAuth2 login - redirects to IdP
		addRoute(basePath() + "/login", GET, "Initiate OAuth2 login flow", lrc -> {
			service.login(lrc);
		});

		// OAuth2 callback - receives authorization code from IdP
		addRoute(basePath() + "/callback", GET, "Handle OAuth2 callback from identity provider", lrc -> {
			service.callback(lrc);
		});

		// Logout - clears the session cookie
		addRoute(basePath() + "/logout", GET, "Logout and clear session cookie", lrc -> {
			service.logout(lrc);
		});
	}
}
