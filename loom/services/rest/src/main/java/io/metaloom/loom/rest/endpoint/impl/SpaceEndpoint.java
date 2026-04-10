package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.SpaceEndpointService;

public class SpaceEndpoint extends AbstractEndpoint{

	private static final Logger log = LoggerFactory.getLogger(SpaceEndpoint.class);
	
	private final SpaceEndpointService service;
	private final ModelExamples examples;

	@Inject
	public SpaceEndpoint(SpaceEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "space";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/spaces";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create a new space",
			examples.spaceCreateRequestExample(),
			examples.spaceResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a space",
			examples.spaceUpdateRequestExample(),
			examples.spaceResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a space",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of spaces",
			examples.spaceListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a space",
			null,
			examples.spaceResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});
	}

}
