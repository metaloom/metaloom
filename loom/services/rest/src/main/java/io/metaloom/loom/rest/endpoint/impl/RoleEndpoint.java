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
import io.metaloom.loom.rest.service.impl.RoleEndpointService;

public class RoleEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(RoleEndpoint.class);

	private final ModelExamples examples;
	private final RoleEndpointService service;

	@Inject
	public RoleEndpoint(RoleEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.examples = examples;
		this.service = service;
	}

	@Override
	public String name() {
		return "role";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/roles";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new role",
			examples.roleCreateRequestExample(),
			examples.roleResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a role",
			examples.roleUpdateRequestExample(),
			examples.roleResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a role",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of roles",
			examples.roleListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a role",
			null,
			examples.roleResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
