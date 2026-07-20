package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.UserEndpointService;

public class MeEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(MeEndpoint.class);

	private final UserEndpointService service;
	private final ModelExamples examples;

	@Inject
	public MeEndpoint(UserEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "me";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/me";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Read the currently authenticated user
		addRoute(basePath(), GET,
			"Load the currently authenticated user",
			null,
			examples.userResponseExample(),
			lrc -> {
				service.me(lrc);
			});
	}

}
